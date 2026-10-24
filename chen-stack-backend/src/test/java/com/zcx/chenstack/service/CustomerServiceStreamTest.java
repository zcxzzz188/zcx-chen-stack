package com.zcx.chenstack.service;

import com.zcx.chenstack.controller.AiController;
import com.zcx.chenstack.domain.dto.AiChatRequestDto;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.rag.ProductRagAnswerService;
import com.zcx.chenstack.service.impl.AiServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerServiceStreamTest {
    private ProductRagAnswerService answers;
    private EditableArticleService articles;
    private AiUsageService quota;
    private AiServiceImpl service;
    private AiController controller;
    private CustomerServiceOrchestrator orchestrator;

    @BeforeEach void setup() {
        answers = mock(ProductRagAnswerService.class);
        articles = mock(EditableArticleService.class);
        quota = mock(AiUsageService.class);
        service = new AiServiceImpl();
        ReflectionTestUtils.setField(service, "productRagAnswerService", answers);
        ReflectionTestUtils.setField(service, "aiUsageService", quota);
        orchestrator = new CustomerServiceOrchestrator(answers, articles, quota);
        controller = new AiController();
        ReflectionTestUtils.setField(controller, "aiService", service);
        ReflectionTestUtils.setField(controller, "customerServiceOrchestrator", orchestrator);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(new SysUser().setId(42)), null, List.of()));
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    private AiChatRequestDto request() {
        AiChatRequestDto request = new AiChatRequestDto();
        request.setMessage("怎么登录");
        request.setChatId("same");
        return request;
    }

    @Test void defaultAndLegacyModesKeepConversationAndReserveForServerIdentity() {
        when(quota.tryReserveUsage(42)).thenReturn(true);
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class))).thenAnswer(call -> {
            assertTrue(call.<BooleanSupplier>getArgument(3).getAsBoolean());
            return Flux.just("统一客服回答");
        });
        AiChatRequestDto request = request();
        assertEquals("chat", request.getMode());
        for (String mode : List.of("chat", "rag")) {
            request.setMode(mode);
            assertEquals(List.of("统一客服回答"), controller.customerServiceChat(request).collectList().block());
        }
        assertEquals(List.of("统一客服回答"), service.customerServiceChat("怎么登录", "same").collectList().block());
        verify(answers, times(3)).answer(eq("怎么登录"), eq("same"), eq(42), any(BooleanSupplier.class));
        verify(quota, times(3)).tryReserveUsage(42);
    }

    @Test void malformedOrAnonymousRequestsCannotReachModelOrQuota() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String body : List.of(
                "{\"message\":\"\",\"chatId\":\"same\"}",
                "{\"message\":\"你好\",\"chatId\":\"\"}",
                "{\"message\":\"你好\",\"chatId\":\"same\",\"mode\":\"unknown\"}",
                "{\"message\":\"你好\",\"chatId\":\"same\",\"mode\":null}")) {
            mvc.perform(post("/ai/customer-service/events").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThrows(BlogException.class, () -> service.customerServiceChat("你好", "same", "unexpected"));
        SecurityContextHolder.clearContext();
        for (String mode : List.of("chat", "rag")) {
            assertThrows(BlogException.class, () -> service.customerServiceChat("你好", "same", mode));
        }
        assertThrows(BlogException.class, () -> controller.customerServiceEvents(request()));
        assertThrows(BlogException.class, () -> controller.customerServiceChat(request()));
        verifyNoInteractions(answers, quota, articles);
    }

    @Test void successfulSseKeepsActionsAndCardsAndFinishesExactlyOnce() {
        when(quota.tryReserveUsage(42)).thenReturn(true);
        when(articles.listForUser(42, 1, 5)).thenReturn(new CustomerServiceEvent("ARTICLE_SELECTION", "请选择文章", null,
                List.of(new CustomerServiceEvent.EditableArticle(20, "本人草稿", 1, 0, true)), 1, 5, false, "SUPPORTED"));
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class), any(CustomerServiceTools.class)))
                .thenAnswer(call -> {
                    assertTrue(call.<BooleanSupplier>getArgument(3).getAsBoolean());
                    CustomerServiceTools tools = call.getArgument(4);
                    tools.showNavigation(CustomerServiceTools.Destination.SETTINGS);
                    tools.listMyEditableArticles(1, 5);
                    tools.publishPending();
                    return Flux.just("完整回答");
                });
        var stream = controller.customerServiceEvents(request()).collectList().block(Duration.ofSeconds(5));
        assertTrue(stream.stream().allMatch(event -> "customer-service".equals(event.event())));
        var events = stream.stream().map(event -> event.data()).toList();
        assertEquals(List.of("NAVIGATION_ACTION", "ARTICLE_SELECTION", "TEXT", "DONE"),
                events.stream().map(CustomerServiceEvent::type).toList());
        assertEquals("OPEN_SETTINGS", events.getFirst().action());
        assertEquals(20, events.get(1).articles().getFirst().articleId());
        verify(articles).listForUser(42, 1, 5);
        verify(quota).tryReserveUsage(42);
    }

    @Test void failedSseClearsSensitiveErrorsAndNextRequestFinishesNormally() {
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class), any(CustomerServiceTools.class)))
                .thenReturn(Flux.concat(Flux.just("未完成片段"), Flux.error(new IllegalStateException("INTERNAL_SECRET"))),
                        Flux.just("恢复正常"));
        var failed = orchestrator.chat("问题", "same", 42).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("TEXT", "ERROR_STATE", "DONE"), failed.stream().map(CustomerServiceEvent::type).toList());
        assertFalse(failed.toString().contains("INTERNAL_SECRET"));
        var recovered = orchestrator.chat("重试", "same", 42).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("TEXT", "DONE"), recovered.stream().map(CustomerServiceEvent::type).toList());
        assertEquals("恢复正常", recovered.getFirst().text());
    }

    @Test void synchronousAnswerFailureStillFinishesSseAndAllowsTheNextRequest() {
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class), any(CustomerServiceTools.class)))
                .thenThrow(new IllegalStateException("INTERNAL_SECRET"))
                .thenReturn(Flux.just("恢复正常"));
        var failed = orchestrator.chat("问题", "same", 42).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("ERROR_STATE", "DONE"), failed.stream().map(CustomerServiceEvent::type).toList());
        assertEquals("这次回答未能完成，请重试。", failed.getFirst().text());
        assertFalse(failed.toString().contains("INTERNAL_SECRET"));
        var recovered = orchestrator.chat("重试", "same", 42).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("TEXT", "DONE"), recovered.stream().map(CustomerServiceEvent::type).toList());
        assertEquals("恢复正常", recovered.getFirst().text());
    }

    @Test void timedOutAnswerEmitsSanitizedErrorAndExactlyOneDone() {
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class), any(CustomerServiceTools.class)))
                .thenReturn(Flux.concat(Flux.just("未完成片段"),
                        Flux.error(new java.util.concurrent.TimeoutException("INTERNAL_TIMEOUT_DETAILS"))));
        var events = orchestrator.chat("等待", "same", 42).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of("TEXT", "ERROR_STATE", "DONE"), events.stream().map(CustomerServiceEvent::type).toList());
        assertEquals("这次回答用时较长，请稍后重试，或把问题说得更具体一些。", events.get(1).text());
        assertFalse(events.toString().contains("INTERNAL_TIMEOUT_DETAILS"));
    }

    @Test void cancellingSseDisposesUpstreamProviderSubscription() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(answers.answer(anyString(), anyString(), eq(42), any(BooleanSupplier.class), any(CustomerServiceTools.class)))
                .thenReturn(Flux.<String>never().doOnSubscribe(subscription -> subscribed.countDown())
                        .doOnCancel(cancelled::countDown));
        var subscription = orchestrator.chat("等待", "same", 42).subscribe();
        try { assertTrue(subscribed.await(3, TimeUnit.SECONDS)); }
        finally { subscription.dispose(); }
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
    }
}
