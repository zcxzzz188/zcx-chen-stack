package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.service.CustomerServiceError;
import com.zcx.chenstack.service.impl.AiUsageServiceImpl;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实隔离 Redis + 本地模型夹具；只在显式指定端口时运行，不读取正式凭据。 */
class CustomerServiceQuotaAcceptanceIT {
    @Test void allModesShareOneHundredAcrossConnectionsAndServiceRestart() throws Exception {
        int userId = ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
        int port = testPort();
        var mainConnection = connection(port, 0);
        var wikiConnection = connection(port, 1);
        var mainRedis = new StringRedisTemplate(mainConnection);
        var wikiRedis = new StringRedisTemplate(wikiConnection);
        var mainQuota = quota(mainRedis);
        var wikiQuota = sharedQuota(wikiRedis, port);
        try {
            wikiRedis.opsForValue().set("quota-test:wiki-session", "retained");
            var fixture = new ProductRagAnswerServiceTest();
            fixture.setup();
            var wiki = mock(WikiPublicationService.class);
            when(wiki.list(anyString(), isNull(), eq(1), anyInt()))
                    .thenReturn(new WikiViews.Page<>(List.of(new WikiViews.Card(10, "事务知识", "摘要", List.of("事务"), 1, "now")), 1, 1, 50, List.of("事务")));
            when(wiki.read(10)).thenReturn(new WikiViews.PublishedPage(10, "事务知识",
                    "PUBLISHED_WIKI_SENTINEL：事务保证原子性。SEMANTIC_NOT_VERIFIED", 1, "now", List.of("事务"),
                    List.of(new WikiViews.Citation("公开文章", "/user/7/article/1", "v1")), List.of()));
            fixture.respond = prompt -> {
                var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions();
                boolean wikiMode = options.getToolCallbacks().stream()
                        .anyMatch(tool -> "readPublishedWiki".equals(tool.getToolDefinition().name()));
                if (!wikiMode) return Flux.just(fixture.text("自然回答", "stop"));
                if (prompt.getInstructions().stream().noneMatch(org.springframework.ai.chat.messages.ToolResponseMessage.class::isInstance)) {
                    return Flux.just(fixture.tools(fixture.call("readPublishedWiki", "{\"title\":\"事务知识\"}")));
                }
                return Flux.just(fixture.text("事务保证原子性，语义仍需核查。", "stop"));
            };
            var modes = List.of("chat", "rag", "wiki");
            var answers = modes.stream().map(mode -> new ProductRagAnswerService(fixture.knowledge,
                    ChatClient.create(fixture.model), fixture.memory, null,
                    new AiKnowledgeProperties(mode), wiki)).toList();
            assertEquals(100, mainQuota.getRemainingQuota(userId));
            for (int request = 1; request <= 99; request++) {
                int index = (request - 1) % 3;
                var usage = index == 2 ? wikiQuota : mainQuota;
                assertEquals(List.of(index == 2 ? "事务保证原子性，语义仍需核查。" : "自然回答"), answers.get(index)
                        .answer(index == 2 ? "事务是什么" : "你好", "shared-" + request, userId, () -> usage.tryReserveUsage(userId))
                        .collectList().block(Duration.ofSeconds(5)));
                if (request == 1) assertEquals(99, mainQuota.getRemainingQuota(userId));
            }
            assertEquals(1, mainQuota.getRemainingQuota(userId));
            assertEquals(1, wikiQuota.getRemainingQuota(userId));
            ReflectionTestUtils.invokeMethod(wikiQuota, "closeQuotaStore");
            wikiQuota = sharedQuota(wikiRedis, port);
            var restarted = wikiQuota;
            assertEquals(1, restarted.getRemainingQuota(userId));
            assertEquals(List.of("事务保证原子性，语义仍需核查。"), answers.get(2)
                    .answer("事务是什么", "after-restart", userId, () -> restarted.tryReserveUsage(userId))
                    .collectList().block(Duration.ofSeconds(5)));
            assertEquals(0, mainQuota.getRemainingQuota(userId));
            int modelCalls = mockingDetails(fixture.model).getInvocations().size();
            var exceeded = assertThrows(RuntimeException.class, () -> answers.get(1)
                    .answer("你好", "request-101", userId, () -> mainQuota.tryReserveUsage(userId))
                    .collectList().block(Duration.ofSeconds(5)));
            assertEquals(CustomerServiceError.DAILY_LIMIT_REACHED, CustomerServiceError.classify(exceeded));
            assertEquals(modelCalls, mockingDetails(fixture.model).getInvocations().size());
            assertEquals("100", mainRedis.opsForValue().get(RedisConstants.AiUsage + userId));
            assertNull(wikiRedis.opsForValue().get(RedisConstants.AiUsage + userId));
            assertEquals("retained", wikiRedis.opsForValue().get("quota-test:wiki-session"));
            assertEquals(100, restarted.getRemainingQuota((userId + 1)));
            assertTrue(restarted.tryReserveUsage((userId + 1)));
            assertEquals(99, mainQuota.getRemainingQuota((userId + 1)));
        } finally {
            ReflectionTestUtils.invokeMethod(wikiQuota, "closeQuotaStore");
            mainConnection.destroy();
            wikiConnection.destroy();
        }
    }

    @Test void failureCancellationAndConcurrentDuplicateEachKeepAdmissionSemantics() throws Exception {
        int userId = ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
        var connection = connection(testPort(), 0);
        var usage = quota(new StringRedisTemplate(connection));
        try {
            var fixture = new ProductRagAnswerServiceTest();
            fixture.setup();
            fixture.respond = prompt -> Flux.error(new IllegalStateException("fixture failure"));
            assertThrows(RuntimeException.class, () -> fixture.service
                    .answer("你好", "failed", userId, () -> usage.tryReserveUsage(userId))
                    .collectList().block(Duration.ofSeconds(5)));
            assertEquals(99, usage.getRemainingQuota(userId));

            var started = new CountDownLatch(1);
            fixture.respond = prompt -> { started.countDown(); return Flux.never(); };
            var pending = fixture.service.answer("你好", "pending", (userId + 1), () -> usage.tryReserveUsage((userId + 1)))
                    .subscribe(value -> { }, error -> { });
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertEquals(99, usage.getRemainingQuota((userId + 1)));
                assertThrows(RuntimeException.class, () -> fixture.service
                        .answer("你好", "pending", (userId + 1), () -> usage.tryReserveUsage((userId + 1)))
                        .collectList().block(Duration.ofSeconds(3)));
                assertEquals(99, usage.getRemainingQuota((userId + 1)));
            } finally {
                pending.dispose();
            }
            assertEquals(99, usage.getRemainingQuota((userId + 1)));
            fixture.respond = prompt -> Flux.just(fixture.text("自然回答", "stop"));
            assertEquals(List.of("自然回答"), fixture.service
                    .answer("你好", "pending", (userId + 1), () -> usage.tryReserveUsage((userId + 1)))
                    .collectList().block(Duration.ofSeconds(5)));
            assertEquals(98, usage.getRemainingQuota((userId + 1)));
        } finally {
            connection.destroy();
        }
    }

    private int testPort() {
        int port = Integer.parseInt(System.getProperty("chen.test.redis.port"));
        assertTrue(port > 0 && port != 6379);
        return port;
    }

    private LettuceConnectionFactory connection(int port, int database) {
        var config = new RedisStandaloneConfiguration("127.0.0.1", port);
        config.setDatabase(database);
        var connection = new LettuceConnectionFactory(config);
        connection.afterPropertiesSet();
        connection.start();
        return connection;
    }

    private AiUsageServiceImpl quota(StringRedisTemplate redis) {
        var usage = new AiUsageServiceImpl();
        ReflectionTestUtils.setField(usage, "stringRedisTemplate", redis);
        return usage;
    }

    private AiUsageServiceImpl sharedQuota(StringRedisTemplate defaultRedis, int port) {
        var usage = quota(defaultRedis);
        ReflectionTestUtils.setField(usage, "quotaRedisHost", "127.0.0.1");
        ReflectionTestUtils.setField(usage, "quotaRedisPort", port);
        ReflectionTestUtils.invokeMethod(usage, "configureQuotaStore");
        return usage;
    }
}
