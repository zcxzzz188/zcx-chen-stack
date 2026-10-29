package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.AiConfig;
import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.service.AiUsageService;
import com.zcx.chenstack.service.CustomerServiceOrchestrator;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual SDK wire protocol against synthetic loopback responses; no real-provider quality claim. */
class DeepSeekWireContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TITLE = "辰栈账号登录指南";
    private static final String FACTS = "WIRE_WIKI_V1：普通用户从用户端输入用户名或邮箱、密码、图片验证码；忘记密码使用登录页入口。";
    private static final String ANSWER = "请从用户端登录，填写用户名或邮箱、密码和图片验证码；忘记密码请使用登录页入口。";
    @TempDir Path directory;

    @Test void prematurelyBuiltOptionsOverrideConfiguredModelAndAreRejected() throws Exception {
        try (WireProvider provider = new WireProvider(false)) {
            ReleaseProviderBudget budget = budget();
            try {
                assertThrows(RuntimeException.class, () -> ChatClient.create(model(provider, budget)).prompt()
                        .user("Synthetic question")
                        .options(OpenAiChatOptions.builder().maxTokens(2048).build().mutate())
                        .stream().content().collectList().block(Duration.ofSeconds(20)));
                assertEquals(1, provider.requests.size());
                assertEquals("gpt-5-mini", provider.requests.getFirst().path("model").asText());
                assertEquals(Map.of("embedding", 0L, "chat", 1L), budget.snapshot());
            } finally {
                provider.export("offline-wire-regression.json");
            }
        }
    }

    @Test void serializedWikiToolRoundTripPreservesContractAndPhysicalBudget() throws Exception {
        try (WireProvider provider = new WireProvider(false)) {
            ReleaseProviderBudget budget = budget();
            var fixture = fixture(provider, budget);
            try {
                List<CustomerServiceEvent> events = fixture.stream.chat("登录失败或忘记密码怎么办？", "wire-success", 7)
                        .collectList().block(Duration.ofSeconds(20));
                assertNotNull(events);
                assertEquals(List.of("TEXT", "KNOWLEDGE_STATUS", "KNOWLEDGE_SOURCES", "DONE"), events.stream().map(CustomerServiceEvent::type).toList());
                assertEquals(ANSWER, events.getFirst().text());
                assertEquals(List.of(new CustomerServiceEvent.KnowledgeSource("用户名或邮箱登录", "/user/7/article/1", "v1")), events.get(2).sources());
                assertEquals(3, provider.requests.size());
                assertEquals(Map.of("embedding", 0L, "chat", 3L), budget.snapshot());
                verify(fixture.wiki, atLeast(2)).read(1);
                verify(fixture.base.knowledge, never()).retrieve(anyString());
                verify(fixture.base.knowledge, never()).reviewedCatalog();

                JsonNode scope = provider.requests.get(0);
                JsonNode first = provider.requests.get(1);
                JsonNode finalRound = provider.requests.get(2);
                assertWireCommon(scope, 16);
                assertWireCommon(first, 2048);
                assertWireCommon(finalRound, 2048);
                assertTrue(scope.path("tools").isMissingNode() || scope.path("tools").isEmpty());
                assertEquals(4, first.path("tools").size());
                assertToolSchema(first);
                assertTrue(first.path("tool_choice").isMissingNode() || "auto".equals(first.path("tool_choice").asText()));
                assertFalse(first.path("messages").findValuesAsText("role").contains("tool"));

                JsonNode assistant = null;
                JsonNode result = null;
                for (JsonNode message : finalRound.path("messages")) {
                    if ("assistant".equals(message.path("role").asText()) && !message.path("tool_calls").isEmpty()) assistant = message;
                    if ("tool".equals(message.path("role").asText())) result = message;
                }
                assertNotNull(assistant);
                assertNotNull(result);
                assertEquals("call_wiki", assistant.path("tool_calls").get(0).path("id").asText());
                assertEquals("readPublishedWiki", assistant.path("tool_calls").get(0).path("function").path("name").asText());
                assertEquals("call_wiki", result.path("tool_call_id").asText());
                assertTrue(result.path("content").asText().contains(FACTS));
                assertTrue(result.path("content").asText().contains("版本：1"));
            } finally {
                provider.export("offline-wire-success.json");
            }
        }
    }

    @Test void firstToolRequest400DoesNotRetryAndEndsAsAnErrorRatherThanAnAnswer() throws Exception {
        try (WireProvider provider = new WireProvider(true)) {
            ReleaseProviderBudget budget = budget();
            var fixture = fixture(provider, budget);
            try {
                List<CustomerServiceEvent> events = fixture.stream.chat("登录失败怎么办？", "wire-error", 7)
                        .collectList().block(Duration.ofSeconds(20));
                assertNotNull(events);
                assertEquals(List.of("ERROR_STATE", "DONE"), events.stream().map(CustomerServiceEvent::type).toList());
                assertEquals("MODEL_SERVICE_ERROR", events.getFirst().errorCode());
                assertEquals(2, provider.requests.size());
                assertEquals(Map.of("embedding", 0L, "chat", 2L), budget.snapshot());
                verify(fixture.wiki, never()).read(anyLong());
                assertTrue(fixture.base.memory.get("wiki:7:wire-error").isEmpty());
            } finally {
                provider.export("offline-wire-error.json");
            }
        }
    }

    private void assertWireCommon(JsonNode request, int maxTokens) {
        assertEquals("deepseek-flash", request.path("model").asText());
        assertTrue(request.path("stream").asBoolean());
        assertTrue(request.path("stream_options").path("include_usage").asBoolean());
        assertEquals("disabled", request.path("thinking").path("type").asText());
        assertEquals(maxTokens, request.path("max_tokens").asInt());
        assertTrue(request.path("reasoning_effort").isMissingNode());
        assertTrue(request.path("messages").isArray());
        for (JsonNode message : request.path("messages")) {
            assertTrue(List.of("system", "user", "assistant", "tool").contains(message.path("role").asText()));
        }
    }

    private void assertToolSchema(JsonNode request) {
        boolean found = false;
        for (JsonNode tool : request.path("tools")) {
            assertEquals("function", tool.path("type").asText());
            JsonNode function = tool.path("function");
            assertFalse(function.path("strict").asBoolean());
            assertTrue(function.path("name").asText().matches("[a-zA-Z0-9_-]{1,64}"));
            assertEquals("object", function.path("parameters").path("type").asText());
            if ("readPublishedWiki".equals(function.path("name").asText())) {
                found = true;
                assertEquals("string", function.path("parameters").path("properties").path("title").path("type").asText());
                assertTrue(function.path("parameters").path("required").toString().contains("title"));
            }
        }
        assertTrue(found);
    }

    private ReleaseProviderBudget budget() throws IOException {
        Path ledger = directory.resolve("attempts.ledger");
        Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic-wire|0|6\n");
        return new ReleaseProviderBudget(ledger.toString(), "synthetic-wire", 0, 6);
    }

    private Fixture fixture(WireProvider provider, ReleaseProviderBudget budget) {
        var base = new ProductRagAnswerServiceTest();
        base.setup();
        when(base.knowledge.chatTimeoutSeconds()).thenReturn(20);
        WikiPublicationService wiki = mock(WikiPublicationService.class);
        WikiViews.Card card = new WikiViews.Card(1, TITLE, "固定登录指南", List.of("登录"), 1, "now");
        WikiViews.PublishedPage page = new WikiViews.PublishedPage(1, TITLE, FACTS, 1, "now", List.of("登录"),
                List.of(new WikiViews.Citation("用户名或邮箱登录", "/user/7/article/1", "v1")), List.of());
        when(wiki.list(anyString(), isNull(), eq(1), anyInt())).thenReturn(new WikiViews.Page<>(List.of(card), 1, 1, 50, List.of("登录")));
        when(wiki.read(1)).thenReturn(page);
        base.service = new ProductRagAnswerService(base.knowledge, ChatClient.create(model(provider, budget)),
                base.memory, budget, new AiKnowledgeProperties("wiki"), wiki, 3);
        AiUsageService usage = mock(AiUsageService.class);
        when(usage.tryReserveUsage(7)).thenReturn(true);
        return new Fixture(base, wiki, new CustomerServiceOrchestrator(base.service, base.articles, usage));
    }

    private OpenAiChatModel model(WireProvider provider, ReleaseProviderBudget budget) {
        AiConfig config = new AiConfig();
        ReflectionTestUtils.setField(config, "apiKey", "synthetic-wire-key");
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + provider.server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "model", "deepseek-flash");
        ReflectionTestUtils.setField(config, "temperature", 0.7);
        ReflectionTestUtils.setField(config, "maxTokens", 2048);
        ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
        return config.openAiChatModel(budget);
    }

    private record Fixture(ProductRagAnswerServiceTest base, WikiPublicationService wiki, CustomerServiceOrchestrator stream) { }

    private static final class WireProvider implements AutoCloseable {
        private final HttpServer server;
        private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        WireProvider(boolean rejectFirstBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try {
                    requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                    int attempt = requests.size();
                    byte[] response;
                    int status = 200;
                    boolean modelRejected = !"deepseek-flash".equals(requests.getLast().path("model").asText());
                    if (modelRejected || rejectFirstBody && attempt == 2) {
                        status = 400;
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        response = JSON.writeValueAsBytes(Map.of("error", Map.of("type", "invalid_request_error",
                                "param", modelRejected ? "model" : "tools[0].function.parameters",
                                "message", modelRejected ? "Synthetic invalid model" : "Synthetic invalid schema")));
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        Map<String, Object> delta = attempt == 1 ? Map.of("role", "assistant", "content", "USER_HELP")
                                : attempt == 2 ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call_wiki", "type", "function",
                                        "function", Map.of("name", "readPublishedWiki", "arguments", JSON.writeValueAsString(Map.of("title", TITLE))))))
                                : Map.of("role", "assistant", "content", ANSWER);
                        String finish = attempt == 2 ? "tool_calls" : "stop";
                        response = (chunk(delta, null) + chunk(Map.of(), finish)
                                + "data: {\"id\":\"synthetic\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n"
                                + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    }
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        private static String chunk(Map<String, Object> delta, String finish) throws IOException {
            var chunk = JSON.createObjectNode();
            chunk.put("id", "synthetic").put("object", "chat.completion.chunk").put("created", 1).put("model", "deepseek-flash");
            var choice = chunk.putArray("choices").addObject().put("index", 0);
            choice.set("delta", JSON.valueToTree(delta));
            if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
            return "data: " + JSON.writeValueAsString(chunk) + "\n\n";
        }

        private void export(String name) throws IOException {
            String output = System.getProperty("chen.wire.captureDirectory");
            if (output == null || output.isBlank()) return;
            Path folder = Path.of(output);
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(requests));
        }

        @Override public void close() { server.stop(0); }
    }
}
