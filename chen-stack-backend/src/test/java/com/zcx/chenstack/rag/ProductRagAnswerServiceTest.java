package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.service.CustomerServiceTools;
import com.zcx.chenstack.service.EditableArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Protocol/security checks only. Loopback model responses do not establish real-provider quality. */
class ProductRagAnswerServiceTest {
    ChatModel model;
    RagKnowledgeService knowledge;
    EditableArticleService articles;
    MessageWindowChatMemory memory;
    ProductRagAnswerService service;
    KnowledgeCatalog catalog;
    final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    final List<CustomerServiceEvent> events = new CopyOnWriteArrayList<>();
    final AtomicInteger reservations = new AtomicInteger();
    Function<Prompt, Flux<ChatResponse>> respond;
    Function<Prompt, Flux<ChatResponse>> scopeRespond;

    @BeforeEach void setup() {
        catalog = new KnowledgeCatalog(List.of(new KnowledgeDocument("draft", "草稿帮助",
                "在文章编辑器点击保存草稿。草稿仅本人可见，发布后按审核流程处理。", "fixture-1", "PLATFORM_HELP",
                "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("草稿"))));
        knowledge = mock(RagKnowledgeService.class);
        when(knowledge.chatTimeoutSeconds()).thenReturn(5);
        when(knowledge.reviewedCatalog()).thenAnswer(invocation -> catalog.lifecycle().snapshot());
        when(knowledge.retrieve(anyString())).thenReturn(new KnowledgeEvidence(catalog.chunks(), catalog));
        articles = mock(EditableArticleService.class);
        when(articles.listForUser(7, 1, 5)).thenReturn(new CustomerServiceEvent("ARTICLE_SELECTION", "请选择文章", null,
                List.of(new CustomerServiceEvent.EditableArticle(73915, "PRIVATE_TITLE_SENTINEL", 1, 0, true)), 1, 5, false, "SUPPORTED"));
        model = mock(ChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("protocol-fixture").build());
        scopeRespond = prompt -> Flux.just(text("USER_HELP", "stop"));
        respond = prompt -> Flux.just(text("自然回答", "stop"));
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (prompt.getSystemMessage().getText().startsWith("你是用户端客服的请求范围分类器")) return scopeRespond.apply(prompt);
            prompts.add(prompt);
            return respond.apply(prompt);
        });
        memory = MessageWindowChatMemory.builder().maxMessages(100).build();
        service = new ProductRagAnswerService(knowledge, ChatClient.create(model), memory);
    }

    ChatResponse text(String text, String reason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(reason).build())));
    }

    AssistantMessage.ToolCall call(String name, String args) {
        return new AssistantMessage.ToolCall("call_" + prompts.size() + "_" + name, "function", name, args);
    }

    ChatResponse tools(AssistantMessage.ToolCall... calls) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(calls)).build(), ChatGenerationMetadata.builder().finishReason("tool_calls").build())));
    }

    Flux<String> answer(String question, String chat, int user) {
        return service.answer(question, chat, user, () -> { reservations.incrementAndGet(); return true; },
                new CustomerServiceTools(user, articles, events::add));
    }

    String run(String question, String chat) {
        return String.join("", answer(question, chat, 7).collectList().block(Duration.ofSeconds(8)));
    }

    @Test void revokedKnowledgeAnswerIsNotReusedAsFollowupContext() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("searchPlatformHelp", "{\"query\":\"如何保存草稿\"}")) : text("OLD_RULE_RESPONSE", "stop"));
        assertEquals("OLD_RULE_RESPONSE", run("怎么先留着内容", "history-revoke"));
        catalog.lifecycle().revoke("draft", catalog.epoch());
        respond = prompt -> {
            assertTrue(prompt.getInstructions().stream().noneMatch(message -> message.getText().contains("OLD_RULE_RESPONSE")));
            return Flux.just(text("请重新核实当前资料", "stop"));
        };
        assertEquals("请重新核实当前资料", run("接着说刚才那个", "history-revoke"));
    }

    @Test void revocationDuringFollowupAlsoGuardsPreviouslyRetrievedKnowledge() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("searchPlatformHelp", "{\"query\":\"如何保存草稿\"}")) : text("旧知识回答", "stop"));
        assertEquals("旧知识回答", run("怎么先保存", "followup-revoke"));
        respond = prompt -> {
            catalog.lifecycle().revoke("draft", catalog.epoch());
            return Flux.just(text("不得释放的旧规则", "stop"));
        };
        assertThrows(RuntimeException.class, () -> run("简短复述刚才的规则", "followup-revoke"));
        assertEquals(2, memory.get("7:followup-revoke").size());
    }

    @Test void greetingsArithmeticAndUnfamiliarWordsAllReachModel() {
        for (String question : List.of("你好", "谢谢", "1+2", "把上次写的那点东西帮我翻出来呗")) {
            assertEquals("自然回答", run(question, "any-" + prompts.size()));
            assertEquals(question, prompts.getLast().getUserMessage().getText());
        }
        assertEquals(4, prompts.size());
        assertEquals(4, reservations.get());
        verifyNoInteractions(articles);
    }

    @Test void helpAndPrivateBusinessToolsCanCooperateWithoutSendingPrivateResultsToModel() {
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(
                    call("searchPlatformHelp", "{\"query\":\"怎样保存草稿并找回本人文章\"}"),
                    call("listMyEditableArticles", "{\"page\":1,\"pageSize\":5}")));
            String history = prompt.getInstructions().toString();
            assertTrue(history.contains("草稿仅本人可见"));
            assertTrue(history.contains("本页1篇"));
            assertFalse(history.contains("PRIVATE_TITLE_SENTINEL"));
            assertFalse(history.contains("73915"));
            return Flux.just(text("草稿仅本人可见，文章已列在卡片中。", "stop"));
        };
        assertTrue(run("能先找回我的文字，再教我存成草稿吗", "cooperate").contains("卡片"));
        verify(articles).listForUser(7, 1, 5);
        verify(knowledge).retrieve("怎样保存草稿并找回本人文章");
        assertEquals(2, prompts.size());
        assertEquals(1, reservations.get());
        assertTrue(events.stream().anyMatch(event -> "ARTICLE_SELECTION".equals(event.type())
                && "PRIVATE_TITLE_SENTINEL".equals(event.articles().getFirst().title())));
        assertFalse(memory.get("7:cooperate").toString().contains("PRIVATE_TITLE_SENTINEL"));
        assertFalse(memory.get("7:cooperate").toString().contains("73915"));
    }

    @Test void userAndConversationMemoryAreIsolated() {
        run("FIRST_USER_PRIVATE_QUESTION", "same-id");
        answer("SECOND_USER_QUESTION", "same-id", 8).collectList().block(Duration.ofSeconds(8));
        answer("ANOTHER_CHAT_QUESTION", "different-id", 7).collectList().block(Duration.ofSeconds(8));
        assertFalse(prompts.get(1).getInstructions().toString().contains("FIRST_USER_PRIVATE_QUESTION"));
        assertFalse(prompts.get(2).getInstructions().toString().contains("FIRST_USER_PRIVATE_QUESTION"));
        run("接着说", "same-id");
        assertTrue(prompts.getLast().getInstructions().toString().contains("FIRST_USER_PRIVATE_QUESTION"));
    }

    @Test void truncatedEmptyAndProviderErrorsNeverEnterHistory() {
        respond = prompt -> Flux.just(text("未完成的内容", "length"));
        assertThrows(RuntimeException.class, () -> run("问题", "truncated"));
        assertTrue(memory.get("7:truncated").isEmpty());
        respond = prompt -> Flux.just(text("", "stop"));
        assertThrows(RuntimeException.class, () -> run("问题", "empty"));
        assertTrue(memory.get("7:empty").isEmpty());
        respond = prompt -> Flux.error(new IllegalStateException("fixture provider disconnected"));
        assertThrows(RuntimeException.class, () -> run("问题", "error"));
        assertTrue(memory.get("7:error").isEmpty());
    }

    @Test void repeatedToolCallsStopAfterSixModelRounds() {
        respond = prompt -> Flux.just(tools(call("searchPlatformHelp", "{\"query\":\"保存草稿\"}")));
        assertThrows(RuntimeException.class, () -> run("查草稿规则", "bounded"));
        assertEquals(6, prompts.size());
        verify(knowledge).retrieve("保存草稿");
        assertTrue(memory.get("7:bounded").isEmpty());
    }

    @Test void ninthParallelToolIsRejectedBeforeAnyToolExecutes() {
        respond=prompt -> Flux.just(tools(java.util.stream.IntStream.range(0,9)
                .mapToObj(i -> call("showNavigation","{\"destination\":\"LOGIN\"}"))
                .toArray(AssistantMessage.ToolCall[]::new)));
        assertThrows(RuntimeException.class,()->run("工具数量边界","parallel-limit"));
        assertEquals(1,prompts.size());assertTrue(events.isEmpty());
        assertTrue(memory.get("7:parallel-limit").isEmpty());verifyNoInteractions(articles);
    }

    @Test void sourceRevocationDuringGenerationPreventsAnyFinalTextRelease() {
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(call("searchPlatformHelp", "{\"query\":\"草稿规则\"}")));
            catalog.lifecycle().revoke("draft", catalog.epoch());
            return Flux.just(text("REVOKED_SOURCE_TEXT", "stop"));
        };
        List<String> released = new CopyOnWriteArrayList<>();
        assertThrows(RuntimeException.class, () -> answer("草稿怎样保存", "revoked", 7)
                .doOnNext(released::add).collectList().block(Duration.ofSeconds(8)));
        assertTrue(released.isEmpty());
        assertTrue(memory.get("7:revoked").isEmpty());
    }

    @Test void toolFailureIsSanitizedAndAvailableForNaturalRecovery() {
        when(articles.listForUser(7, 1, 5)).thenThrow(new IllegalStateException("SQL_PASSWORD_SENTINEL"));
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(call("listMyEditableArticles", "{\"page\":1,\"pageSize\":5}")));
            assertTrue(prompt.getInstructions().stream().filter(m -> m instanceof ToolResponseMessage)
                    .anyMatch(m -> m.toString().contains("查询失败")));
            assertFalse(prompt.getInstructions().toString().contains("SQL_PASSWORD_SENTINEL"));
            return Flux.just(text("这次没有查到可用结果，可以点击重试。", "stop"));
        };
        assertTrue(run("找找我写过的内容", "tool-error").contains("重试"));
        assertTrue(events.stream().anyMatch(event -> "ERROR_STATE".equals(event.type())));
        verify(articles).listForUser(7, 1, 5);
    }

    @Test void cancellationDoesNotWriteMemoryAndReleasesConversationLock() {
        respond = prompt -> Flux.never();
        var subscription = answer("先等一下", "cancel", 7).subscribe();
        assertThrows(RuntimeException.class, () -> run("并发请求", "cancel"));
        subscription.dispose();
        assertTrue(memory.get("7:cancel").isEmpty());
        respond = prompt -> Flux.just(text("可以继续", "stop"));
        assertEquals("可以继续", run("继续", "cancel"));
    }

    @Test void timeoutDoesNotWriteHistoryAndNextRequestCanContinue() {
        when(knowledge.chatTimeoutSeconds()).thenReturn(1);
        respond = prompt -> Flux.never();
        assertThrows(RuntimeException.class, () -> run("一直等待", "timeout"));
        assertTrue(memory.get("7:timeout").isEmpty());
        respond = prompt -> Flux.just(text("本次正常", "stop"));
        assertEquals("本次正常", run("再问一次", "timeout"));
    }

    @Test void unknownToolCannotReachBusinessServicesOrWriteHistory() {
        respond = prompt -> Flux.just(tools(call("deleteAnyUsersArticle", "{\"userId\":8,\"articleId\":73915}")));
        assertThrows(RuntimeException.class, () -> run("越权工具请求", "unknown-tool"));
        verifyNoInteractions(articles);
        assertTrue(memory.get("7:unknown-tool").isEmpty());
    }

    @Test void realSpringAiLoopbackSseExecutesToolThenMakesSecondModelRequest() throws Exception {
        List<String> wires = new CopyOnWriteArrayList<>();
        List<String> scopeWires = new CopyOnWriteArrayList<>();
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (json.readTree(request).path("messages").get(0).path("content").asText().startsWith("你是用户端客服的请求范围分类器")) {
                scopeWires.add(request);
                byte[] body = ("data: " + json.writeValueAsString(Map.of("id", "scope-loopback", "object", "chat.completion.chunk", "created", 1,
                        "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "USER_HELP"), "finish_reason", "stop")))) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close(); return;
            }
            wires.add(request);
            boolean first = wires.size() == 1;
            Object delta = first ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                    "id", "call_private", "type", "function", "function", Map.of("name", "listMyEditableArticles",
                            "arguments", "{\"page\":1,\"pageSize\":5}"))))
                    : Map.of("role", "assistant", "content", "已查到本人文章，请点击卡片编辑。");
            String body = "data: " + json.writeValueAsString(Map.of("id", "protocol-loopback", "object", "chat.completion.chunk",
                    "created", 1, "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", delta,
                            "finish_reason", first ? "tool_calls" : "stop")))) + "\n\ndata: [DONE]\n\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            OpenAiChatModel loopback = OpenAiChatModel.builder().openAiApi(OpenAiApi.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).completionsPath("/chat/completions")
                            .apiKey("offline-fixture-only").build())
                    .defaultOptions(OpenAiChatOptions.builder().model("fixture").build())
                    .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            service = new ProductRagAnswerService(knowledge, ChatClient.create(loopback), memory);
            assertTrue(run("我之前码的那些文字还能接着改吗", "wire").contains("卡片"));
            assertEquals(2, wires.size());
            assertEquals(1, scopeWires.size());
            assertFalse(scopeWires.getFirst().contains("PRIVATE_TITLE_SENTINEL"));
            verify(articles).listForUser(7, 1, 5);
            assertTrue(wires.get(1).contains("tool_call_id"));
            for (String wire : wires) {
                assertFalse(wire.contains("PRIVATE_TITLE_SENTINEL"));
                assertFalse(wire.contains("73915"));
                assertFalse(wire.contains("userId"));
            }
        } finally { server.stop(0); }
    }
}
