package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.service.CustomerServiceTools;
import com.zcx.chenstack.service.CustomerServiceOrchestrator;
import com.zcx.chenstack.service.AiUsageService;
import com.zcx.chenstack.service.EditableArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import com.zcx.chenstack.config.AiConfig;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        when(knowledge.contextByteBudget()).thenReturn(6000);
        when(knowledge.reviewedCatalog()).thenAnswer(invocation -> catalog.lifecycle().snapshot());
        when(knowledge.retrieveByTitle(anyString())).thenAnswer(invocation -> new KnowledgeEvidence(catalog.chunks().stream()
                .filter(chunk -> catalog.source((String) chunk.getMetadata().get("source_id")).title().equals(invocation.getArgument(0))).toList(), catalog));
        when(knowledge.retrieve(anyString())).thenReturn(new KnowledgeEvidence(catalog.chunks(), catalog));
        articles = mock(EditableArticleService.class);
        when(articles.listForUser(7, 1, 5)).thenReturn(new CustomerServiceEvent("ARTICLE_SELECTION", "请选择文章", null,
                List.of(new CustomerServiceEvent.EditableArticle(73915, "PRIVATE_TITLE_SENTINEL", 1, 0, true)), 1, 5, false, "SUPPORTED"));
        model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("protocol-fixture").build());
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

    @Test void promisedNavigationIsRecreatedForEverySseReplyWithoutAnotherModelRound() {
        respond = prompt -> Flux.just(text("登录后查看审核状态。下方按钮可直接进入文章管理。", "stop"));
        AiUsageService quota = mock(AiUsageService.class);
        when(quota.tryReserveUsage(7)).thenReturn(true);
        CustomerServiceOrchestrator stream = new CustomerServiceOrchestrator(service, articles, quota);
        for (int i = 0; i < 2; i++) {
            List<CustomerServiceEvent> reply = stream.chat("如果审核没有通过，我该在哪里看结果并修改？", "nav-followup", 7)
                    .collectList().block(Duration.ofSeconds(8));
            assertEquals(List.of("TEXT", "NAVIGATION_ACTION", "DONE"), reply.stream().map(CustomerServiceEvent::type).toList());
            assertEquals("OPEN_ARTICLE_MANAGE", reply.get(1).action());
        }
        assertEquals(2, prompts.size());
        verifyNoInteractions(articles);
    }

    @Test void explicitNavigationAndFinalPromiseProduceOnlyOneAction() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("showNavigation", "{\"destination\":\"ARTICLE_MANAGE\"}"))
                : text("下方按钮可直接进入文章管理。", "stop"));
        run("打开文章管理", "nav-once");
        assertEquals(1, events.stream().filter(event -> "NAVIGATION_ACTION".equals(event.type())).count());
    }

    @Test void wikiPageButtonDescriptionsPassTheActualTwoRoundProductAnswerWithoutInventingReplyActions() {
        var wiki = mock(com.zcx.chenstack.wiki.WikiPublicationService.class);
        String body = "可在专栏管理页新建和编辑专栏。这里没有添加已有文章按钮。点击“编辑”按钮打开表单。";
        var card = new com.zcx.chenstack.wiki.WikiViews.Card(10, "专栏管理", "摘要", List.of("专栏"), 1, "now");
        var page = new com.zcx.chenstack.wiki.WikiViews.PublishedPage(10, card.title(), body, 1, "now", List.of("专栏"),
                List.of(new com.zcx.chenstack.wiki.WikiViews.Citation("专栏帮助", "/user/7/article/1", "v1")), List.of());
        when(wiki.list(anyString(), isNull(), eq(1), anyInt()))
                .thenReturn(new com.zcx.chenstack.wiki.WikiViews.Page<>(List.of(card), 1, 1, 50, List.of("专栏")));
        when(wiki.read(10)).thenReturn(page);
        service = new ProductRagAnswerService(knowledge, ChatClient.create(model), memory, null,
                new com.zcx.chenstack.config.AiKnowledgeProperties("wiki"), wiki);
        scopeRespond = prompt -> Flux.just(text("PLATFORM_KNOWLEDGE", "stop"));
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("readPublishedWiki", "{\"title\":\"专栏管理\"}")) : text(body, "stop"));
        assertEquals(body, run("如何管理专栏", "wiki-page-buttons"));
        assertEquals(2, prompts.size());
        assertTrue(events.stream().anyMatch(event -> "KNOWLEDGE_SOURCES".equals(event.type())));
        assertTrue(events.stream().noneMatch(event -> "NAVIGATION_ACTION".equals(event.type())));
        assertTrue(memory.get("wiki:7:wiki-page-buttons").toString().contains(body));
        verify(wiki, atLeast(2)).read(10);
        verify(knowledge, never()).retrieve(anyString());
    }

    @Test void failureDiagnosticsExposeOnlyExceptionClassAndFixedReasonCode() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProductRagAnswerService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            respond = prompt -> Flux.just(text("下方有支付中心按钮。", "stop"));
            assertThrows(RuntimeException.class, () -> run("PRIVATE_QUESTION_CANARY", "known-navigation-error"));
            respond = prompt -> Flux.error(new IllegalStateException("PRIVATE_EXCEPTION_MESSAGE_CANARY"));
            assertThrows(RuntimeException.class, () -> run("PRIVATE_QUESTION_CANARY", "unknown-error"));
            var failures = appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("xiaochen failure ")).toList();
            assertEquals(2, failures.size());
            assertEquals("xiaochen failure exception_class=java.lang.IllegalStateException reason_code=NAVIGATION_DESTINATION_UNCONFIRMED", failures.getFirst().getFormattedMessage());
            assertEquals("xiaochen failure exception_class=java.lang.IllegalStateException reason_code=UNCLASSIFIED", failures.getLast().getFormattedMessage());
            for (var failure : failures) {
                assertNull(failure.getThrowableProxy());
                assertFalse(failure.getFormattedMessage().contains("PRIVATE_QUESTION_CANARY"));
                assertFalse(failure.getFormattedMessage().contains("PRIVATE_EXCEPTION_MESSAGE_CANARY"));
                assertFalse(failure.getFormattedMessage().contains("支付中心"));
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void unknownNavigationAndTextOnlyPromisesCannotBeReleasedOrRemembered() {
        respond = prompt -> Flux.just(text("下方按钮可直接进入支付中心。", "stop"));
        assertThrows(RuntimeException.class, () -> run("打开入口", "unknown-page"));
        assertTrue(events.isEmpty());
        assertTrue(memory.get("7:unknown-page").isEmpty());
        respond = prompt -> Flux.just(text("下方按钮可直接进入文章管理。", "stop"));
        assertThrows(RuntimeException.class, () -> service.answer("查看文章", "text-only", 7, () -> true)
                .collectList().block(Duration.ofSeconds(8)));
        assertTrue(memory.get("7:text-only").isEmpty());
    }

    @Test void unrelatedPreparedActionCannotValidateAnUnknownPagePromise() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("showNavigation", "{\"destination\":\"SETTINGS\"}"))
                : text("下方按钮可直接进入支付中心。", "stop"));
        assertThrows(RuntimeException.class, () -> run("查看入口", "unknown-after-tool"));
        assertTrue(events.isEmpty());
        assertTrue(memory.get("7:unknown-after-tool").isEmpty());
    }

    @Test void unknownButtonPromisesCannotBorrowUnrelatedActionsOrArticleCards() {
        for (String promise : List.of("下方有支付中心按钮。", "下方有按钮。", "请看下方文章卡片，旁边有支付中心按钮。",
                "下方按钮可进入文章管理。下方还有支付中心按钮。")) {
            for (boolean withCards : List.of(false, true)) {
                prompts.clear(); events.clear();
                String chat = "unknown-button-" + Math.abs(promise.hashCode()) + "-" + withCards;
                respond = prompt -> Flux.just(prompts.size() == 1
                        ? tools(withCards ? call("listMyEditableArticles", "{\"page\":1,\"pageSize\":5}")
                            : call("showNavigation", "{\"destination\":\"SETTINGS\"}"))
                        : text(promise, "stop"));
                assertThrows(RuntimeException.class, () -> run("查看本人文章和页面入口", chat), promise + withCards);
                assertTrue(events.isEmpty(), promise);
                assertTrue(memory.get("7:" + chat).isEmpty(), promise);
            }
        }
    }

    @Test void knownButtonPromisePreparesItsOwnActionBesideAnUnrelatedAction() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("showNavigation", "{\"destination\":\"SETTINGS\"}"))
                : text("下方按钮可进入文章管理。", "stop"));
        run("查看本人文章入口", "known-after-unrelated");
        assertEquals(List.of("OPEN_SETTINGS", "OPEN_ARTICLE_MANAGE"), events.stream()
                .filter(event -> "NAVIGATION_ACTION".equals(event.type())).map(CustomerServiceEvent::action).toList());
    }

    private void useReviewedProductHelp() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        try (var input = getClass().getClassLoader().getResourceAsStream("knowledge/platform-help.json")) {
            catalog = new KnowledgeCatalog(java.util.Arrays.stream(json.readValue(input, KnowledgeDocument[].class))
                    .filter(source -> PublicationManifest.userSourceIds().contains(source.sourceId()))
                    .map(source -> new KnowledgeDocument(source.sourceId(), source.title(), source.text(), source.version(), source.sourceType(),
                            source.sourceUrl(), "PUBLIC", "DOCUMENT_REVIEWED", source.updatedAt(), source.keywords())).toList());
        }
    }

    @Test void privateConversationTopicIncludesSendingAndRevocationWithoutEmbeddingOrPrivateData() throws Exception {
        useReviewedProductHelp();
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(call("readPlatformHelp", "{\"topicTitle\":\"查看私信会话、已读和删除会话\"}")));
            String facts = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                    .map(Object::toString).reduce("", String::concat);
            assertTrue(facts.contains("Shift+Enter"));
            assertTrue(facts.contains("两分钟"));
            assertTrue(facts.contains("删除会话不能当作撤回"));
            assertTrue(facts.contains("小辰没有读取私人聊天内容或代发私信的工具"));
            return Flux.just(text("请按已核实的私信步骤操作，小辰不能代发。", "stop"));
        };
        run("平台现在支持把我所有私信导出成PDF并自动发邮件吗？", "private-capability");
        for (String title : catalog.sourceIds().stream().filter(id -> id.startsWith("help-private-"))
                .map(id -> catalog.source(id).title()).toList()) verify(knowledge).retrieveByTitle(title);
        verify(knowledge, never()).retrieve(anyString());
        verifyNoInteractions(articles);
    }

    @Test void relatedPrivateTopicsDoNotReplaceTheRequestedEmailSettingsEvidence() throws Exception {
        useReviewedProductHelp();
        String title = catalog.source("help-email-settings").title();
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(call("readPlatformHelp", "{\"topicTitle\":\"" + title + "\"}")));
            String facts = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                    .map(Object::toString).reduce("", String::concat);
            for (String id : List.of("help-email-settings", "help-private-message", "help-private-revoke", "help-private-conversation")) {
                assertTrue(facts.contains(id), id);
            }
            return Flux.just(text("请在个人设置中查看私信邮件通知设置，小辰不能代发私信。", "stop"));
        };
        run("私信邮件提醒在哪里设置，发消息和撤回怎么用？", "private-email");
        verify(knowledge).retrieveByTitle(title);
        verify(knowledge, never()).retrieve(anyString());
        verifyNoInteractions(articles);
    }

    @Test void unanchoredSemanticCandidateRequiresConfirmationInsteadOfSupportedStatus() {
        when(knowledge.hasTopicAnchor(any(KnowledgeEvidence.class), anyString())).thenReturn(false);
        when(knowledge.hasTopicAnchor(any(KnowledgeEvidence.class), eq("评论管理"))).thenReturn(true);
        respond = prompt -> {
            if (prompts.size() == 1) return Flux.just(tools(call("searchPlatformHelp", "{\"query\":\"评论管理\"}")));
            String facts = prompt.getInstructions().stream().filter(message -> message instanceof ToolResponseMessage)
                    .map(Object::toString).reduce("", String::concat);
            assertTrue(facts.contains("尚未确认与原问题为同一业务对象"));
            assertTrue(facts.contains("先澄清"));
            return Flux.just(text("请确认被退回的是文章还是评论？", "stop"));
        };
        assertEquals("请确认被退回的是文章还是评论？", run("被退回的内容怎么改好再提交", "unanchored"));
        verify(knowledge).hasTopicAnchor(any(KnowledgeEvidence.class), eq("被退回的内容怎么改好再提交"));
        assertTrue(events.stream().anyMatch(event -> "UNKNOWN".equals(event.supportStatus())));
        assertTrue(events.stream().noneMatch(event -> "SUPPORTED".equals(event.supportStatus())));
    }

    @Test void ragSourceEventsPreserveVerifiedPublicReferencesWithoutInternalIdsOrUrls() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("readPlatformHelp", "{\"topicTitle\":\"草稿帮助\"}")) : text("草稿仅本人可见。", "stop"));
        assertEquals("草稿仅本人可见。", run("草稿谁能看到", "rag-source-trace"));
        CustomerServiceEvent traced = events.stream().filter(event -> "KNOWLEDGE_SOURCES".equals(event.type()))
                .findFirst().orElseThrow();
        assertEquals(List.of(new CustomerServiceEvent.KnowledgeSource("草稿帮助", null, "fixture-1")), traced.sources());
    }

    private Flux<ChatResponse> platformScope(Prompt prompt) {
        return Flux.just(text(prompt.getSystemMessage().getText().contains("PLATFORM_KNOWLEDGE")
                ? "PLATFORM_KNOWLEDGE" : "USER_HELP", "stop"));
    }

    private void useTwoReviewedTopics() {
        catalog = new KnowledgeCatalog(List.of(catalog.source("draft"), new KnowledgeDocument("current-topic", "当前主题帮助",
                "CURRENT_TOPIC_FACT_CANARY：当前流程需要单独核验，不沿用草稿规则。", "current-1", "PLATFORM_HELP",
                "/", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("当前流程"))));
    }

    private void awaitTerminalCleanup() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(() ->
                ((java.util.Set<?>) ReflectionTestUtils.getField(service, "activeConversations")).isEmpty());
    }

    @Test void platformFollowupCannotReleaseAReplyUsingOnlyPreviousTopicEvidence() {
        useTwoReviewedTopics();
        scopeRespond = this::platformScope;
        AtomicInteger rounds = new AtomicInteger();
        respond = prompt -> Flux.just(switch (rounds.incrementAndGet()) {
            case 1 -> tools(call("readPlatformHelp", "{\"topicTitle\":\"草稿帮助\"}"));
            case 2 -> text("OLD_TOPIC_ANSWER_CANARY", "stop");
            case 3 -> text("UNSUPPORTED_CURRENT_ANSWER_CANARY", "stop");
            case 4 -> tools(call("readPlatformHelp", "{\"topicTitle\":\"当前主题帮助\"}"));
            default -> text("CURRENT_TOPIC_ANSWER_CANARY", "stop");
        });
        assertEquals("OLD_TOPIC_ANSWER_CANARY", run("草稿流程的规则是什么", "current-turn-gate"));
        awaitTerminalCleanup();
        assertEquals("CURRENT_TOPIC_ANSWER_CANARY", run("当前流程需要什么条件", "current-turn-gate"));
        assertEquals(5, prompts.size());
        assertTrue(prompts.get(2).getInstructions().toString().contains("OLD_TOPIC_ANSWER_CANARY"));
        assertTrue(prompts.get(2).getInstructions().toString().contains("草稿仅本人可见"));
        assertFalse(memory.get("7:current-turn-gate").toString().contains("UNSUPPORTED_CURRENT_ANSWER_CANARY"));
        verify(knowledge).retrieveByTitle("当前主题帮助");
    }

    @Test void currentSourcesAndUsageExcludeHistoryButKeepHistoryForUnderstanding(@TempDir Path directory) {
        when(knowledge.chatTimeoutSeconds()).thenReturn(30);
        useTwoReviewedTopics();
        var usage = new NormalAiUsage(true, directory.resolve("current-sources.jsonl").toString());
        service = new ProductRagAnswerService(knowledge, ChatClient.create(model), memory, null,
                new com.zcx.chenstack.config.AiKnowledgeProperties("rag"), null, 6, usage);
        AtomicInteger rounds = new AtomicInteger();
        respond = prompt -> Flux.just(switch (rounds.incrementAndGet()) {
            case 1 -> tools(call("readPlatformHelp", "{\"topicTitle\":\"草稿帮助\"}"));
            case 2 -> text("OLD_CONTEXT_CANARY", "stop");
            case 3 -> tools(call("readPlatformHelp", "{\"topicTitle\":\"当前主题帮助\"}"));
            default -> text("CURRENT_CONTEXT_CANARY", "stop");
        });
        answer("草稿规则", "source-audit", 7).collectList().block(Duration.ofSeconds(35));
        awaitTerminalCleanup();
        events.clear();
        answer("当前流程规则", "source-audit", 7).collectList().block(Duration.ofSeconds(35));
        assertTrue(prompts.get(2).getInstructions().toString().contains("OLD_CONTEXT_CANARY"));
        CustomerServiceEvent traced = events.stream().filter(event -> "KNOWLEDGE_SOURCES".equals(event.type())).findFirst().orElseThrow();
        assertEquals(List.of(new CustomerServiceEvent.KnowledgeSource("当前主题帮助", null, "current-1")), traced.sources());
        var currentAudit = usage.recentKnowledgeUses(20).getFirst();
        assertTrue(currentAudit.get("sources").toString().contains("current-topic"));
        assertFalse(currentAudit.get("sources").toString().contains("draft"));
    }

    @Test void platformFirstTurnRequiresToolsAndReturnsToAutoAfterReadingCurrentEvidence() {
        scopeRespond = this::platformScope;
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("readPlatformHelp", "{\"topicTitle\":\"草稿帮助\"}")) : text("草稿仅本人可见。", "stop"));
        assertEquals("草稿仅本人可见。", run("草稿对谁可见", "first-current-evidence"));
        assertEquals("required", ((OpenAiChatOptions) prompts.getFirst().getOptions()).getToolChoice());
        assertEquals("auto", ((OpenAiChatOptions) prompts.getLast().getOptions()).getToolChoice());
        var names = ((OpenAiChatOptions) prompts.getFirst().getOptions()).getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name()).toList();
        assertTrue(names.containsAll(List.of("readPlatformHelp", "searchPlatformHelp", "showNavigation", "listMyEditableArticles")));
        verify(model, times(3)).stream(any(Prompt.class)); // 同一分类请求，加两次正文请求。
    }

    @Test void ignoredRequiredToolChoiceGetsAtMostOneCorrectionWithinTheOriginalRoundLimit() {
        for (int limit : List.of(1, 2, 6)) {
            var f = new ProductRagAnswerServiceTest(); f.setup();
            f.scopeRespond = f::platformScope;
            f.service = new ProductRagAnswerService(f.knowledge, ChatClient.create(f.model), f.memory, null,
                    new com.zcx.chenstack.config.AiKnowledgeProperties("rag"), null, limit);
            f.respond = prompt -> Flux.just(f.text("UNVERIFIED_ANSWER_CANARY", "stop"));
            assertTrue(f.run("当前功能需要哪些条件", "ignored-required").contains("没有查到"));
            assertEquals(Math.min(2, limit), f.prompts.size());
            assertTrue(f.prompts.stream().allMatch(prompt -> "required".equals(((OpenAiChatOptions) prompt.getOptions()).getToolChoice())));
            assertFalse(f.memory.get("7:ignored-required").toString().contains("UNVERIFIED_ANSWER_CANARY"));
            assertTrue(f.events.stream().anyMatch(event -> "UNKNOWN".equals(event.supportStatus())));
            assertTrue(f.events.stream().noneMatch(event -> "KNOWLEDGE_SOURCES".equals(event.type())));
            verify(f.model, times(1 + Math.min(2, limit))).stream(any(Prompt.class));
        }
    }

    @Test void emptyFailedAndInvalidCurrentReadsCannotReleaseAnInventedKnowledgeAnswer() {
        for (String result : List.of("empty", "failed", "invalid")) {
            var f = new ProductRagAnswerServiceTest(); f.setup();
            f.scopeRespond = f::platformScope;
            if ("empty".equals(result)) when(f.knowledge.retrieveByTitle(anyString())).thenReturn(new KnowledgeEvidence(List.of(), f.catalog));
            if ("failed".equals(result)) when(f.knowledge.retrieveByTitle(anyString())).thenThrow(new RagFailure(RagFailure.Code.INDEX_MISMATCH));
            String title = "invalid".equals(result) ? "不存在的标题" : "草稿帮助";
            f.respond = prompt -> Flux.just(f.prompts.size() == 1
                    ? f.tools(f.call("readPlatformHelp", "{\"topicTitle\":\"" + title + "\"}")) : f.text("INVENTED_RULE_CANARY", "stop"));
            assertTrue(f.run("当前功能规则是什么", result).contains("没有查到"));
            assertEquals(2, f.prompts.size());
            assertFalse(f.memory.get("7:" + result).toString().contains("INVENTED_RULE_CANARY"));
            assertTrue(f.events.stream().noneMatch(event -> "KNOWLEDGE_SOURCES".equals(event.type())));
            if (!"invalid".equals(result)) assertTrue(f.events.stream().anyMatch(event -> "KNOWLEDGE_STATUS".equals(event.type())));
            if ("failed".equals(result)) assertTrue(f.events.stream().anyMatch(event -> "KNOWLEDGE_UNAVAILABLE".equals(event.errorCode())));
        }
    }

    @Test void nonKnowledgeFollowupKeepsContextWithoutClaimingHistoricalSources() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("readPlatformHelp", "{\"topicTitle\":\"草稿帮助\"}")) : text("OLD_CONTEXT_CANARY", "stop"));
        run("草稿规则", "ordinary-followup");
        awaitTerminalCleanup(); events.clear();
        respond = prompt -> Flux.just(text("不客气。", "stop"));
        assertEquals("不客气。", run("谢谢", "ordinary-followup"));
        assertTrue(prompts.getLast().getInstructions().toString().contains("OLD_CONTEXT_CANARY"));
        assertEquals("auto", ((OpenAiChatOptions) prompts.getLast().getOptions()).getToolChoice());
        assertTrue(events.stream().noneMatch(event -> "KNOWLEDGE_SOURCES".equals(event.type())));
        verify(knowledge, times(1)).retrieveByTitle(anyString());
    }

    @Test void revokedKnowledgeAnswerIsNotReusedAsFollowupContext() {
        respond = prompt -> Flux.just(prompts.size() == 1
                ? tools(call("searchPlatformHelp", "{\"query\":\"如何保存草稿\"}")) : text("OLD_RULE_RESPONSE", "stop"));
        assertEquals("OLD_RULE_RESPONSE", run("怎么先留着内容", "history-revoke"));
        catalog.lifecycle().revoke("draft", catalog.epoch());
        respond = prompt -> {
            assertTrue(prompt.getInstructions().stream().noneMatch(message -> message.getText().contains("OLD_RULE_RESPONSE")));
            var turns = prompt.getInstructions().stream().filter(message -> message instanceof org.springframework.ai.chat.messages.UserMessage
                    || message instanceof AssistantMessage).toList();
            assertEquals(3, turns.size());
            assertInstanceOf(AssistantMessage.class, turns.get(1));
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

    @Test void unconfirmedNavigationGetsOneModelCorrectionWithoutPublishingPartialActions() {
        respond = prompt -> Flux.just(text(prompts.size() == 1
                ? "下方有去登录按钮。下方有支付中心按钮。" : "你好，我可以解答辰栈使用问题。", "stop"));
        assertEquals("你好，我可以解答辰栈使用问题。", run("介绍一下你能帮我做什么", "intro-correction"));
        assertEquals(2, prompts.size());
        assertEquals(1, reservations.get());
        assertTrue(prompts.getLast().getInstructions().toString().contains("尚未展示给用户"));
        assertTrue(events.stream().noneMatch(event -> "NAVIGATION_ACTION".equals(event.type())));
        assertFalse(memory.get("7:intro-correction").toString().contains("支付中心"));
        verifyNoInteractions(articles);
    }

    @Test void repeatedUnconfirmedNavigationStillFailsWithinTheOriginalRoundLimit() {
        respond = prompt -> Flux.just(text("下方有支付中心按钮。", "stop"));
        assertThrows(RuntimeException.class, () -> run("你好", "invalid-intro"));
        assertEquals(2, prompts.size());
        assertEquals(1, reservations.get());
        assertTrue(events.isEmpty());
        assertTrue(memory.get("7:invalid-intro").isEmpty());
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

    @Test void insufficientChatBudgetFailsBeforeClassifierOrDailyQuotaReservation(@TempDir Path directory) throws Exception {
        for (int used : new int[]{19, 20}) {
            Path ledger = directory.resolve("budget-" + used + ".ledger");
            Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|0|20\n");
            var budget = new ReleaseProviderBudget(ledger.toString(), "synthetic", 0, 20);
            for (int i = 0; i < used; i++) budget.reserve(ReleaseProviderBudget.Kind.CHAT);
            byte[] before = Files.readAllBytes(ledger);
            service = new ProductRagAnswerService(knowledge, ChatClient.create(model), memory, budget);
            AiUsageService quota = mock(AiUsageService.class);
            var stream = new CustomerServiceOrchestrator(service, articles, quota);
            var reply = stream.chat("你好", "preflight", 7).collectList().block(Duration.ofSeconds(5));
            assertEquals(List.of("ERROR_STATE", "DONE"), reply.stream().map(CustomerServiceEvent::type).toList());
            assertEquals("LOCAL_BUDGET_EXHAUSTED", reply.getFirst().errorCode());
            assertArrayEquals(before, Files.readAllBytes(ledger));
            assertTrue(memory.get("7:preflight").isEmpty());
            verifyNoInteractions(quota, articles);
        }
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test void knowledgeFailureHasAnExplicitWarningWhileRecoveredAnswerStillFinishes() {
        when(knowledge.retrieve(anyString())).thenThrow(new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE));
        respond = prompt -> prompts.size() == 1
                ? Flux.just(tools(call("searchPlatformHelp", "{\"query\":\"保存草稿\"}")))
                : Flux.just(text("知识查询暂不可用，当前不能确认操作规则。", "stop"));
        AiUsageService quota = mock(AiUsageService.class);
        when(quota.tryReserveUsage(7)).thenReturn(true);
        var reply = new CustomerServiceOrchestrator(service, articles, quota).chat("怎么保存草稿", "knowledge-fail", 7)
                .collectList().block(Duration.ofSeconds(8));
        assertEquals("DONE", reply.getLast().type());
        assertTrue(reply.stream().anyMatch(event -> "KNOWLEDGE_STATUS".equals(event.type())
                && "KNOWLEDGE_UNAVAILABLE".equals(event.errorCode()) && event.text().contains("知识查询服务暂时不可用")));
        assertTrue(reply.stream().noneMatch(event -> "ERROR_STATE".equals(event.type()) || "LOCAL_BUDGET_EXHAUSTED".equals(event.errorCode())));
        assertFalse(reply.toString().contains("参考资料"));
    }

    @Test void lastChatSlotOnlyCompletesScopeThenBlocksAnswerBeforeHttp(@TempDir Path directory) throws Exception {
        Path ledger = directory.resolve("attempts.ledger");
        Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|synthetic|0|20\n");
        var budget = new ReleaseProviderBudget(ledger.toString(), "synthetic", 0, 20);
        for (int i = 0; i < 19; i++) budget.reserve(ReleaseProviderBudget.Kind.CHAT);
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("data: {\"id\":\"scope-only\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                    + "\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                    + "\"content\":\"USER_HELP\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            OpenAiChatModel loopback = loopback(server, budget);
            service = new ProductRagAnswerService(knowledge, ChatClient.create(loopback), memory);
            AiUsageService quota = mock(AiUsageService.class);
            when(quota.tryReserveUsage(7)).thenReturn(true);
            var stream = new CustomerServiceOrchestrator(service, articles, quota);
            for (int i = 0; i < 2; i++) {
                var reply = stream.chat("你好", "last-slot", 7).collectList().block(Duration.ofSeconds(8));
                assertEquals(List.of("ERROR_STATE", "DONE"), reply.stream().map(CustomerServiceEvent::type).toList());
                assertEquals("LOCAL_BUDGET_EXHAUSTED", reply.getFirst().errorCode());
                assertEquals(1, requests.size());
                assertEquals(Map.of("embedding", 0L, "chat", 20L), budget.snapshot());
                assertTrue(memory.get("7:last-slot").isEmpty());
            }
            String request = requests.getFirst();
            assertTrue(request.contains("你是用户端客服的请求范围分类器"));
            verifyNoInteractions(articles);
            verify(knowledge, never()).retrieve(anyString());
        } finally { server.stop(0); }
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
            OpenAiChatModel loopback = loopback(server, new ReleaseProviderBudget("", "", 64, 20));
            // 本机 HTTP 客户端冷启动使用与相邻协议测试一致的预算。
            when(knowledge.chatTimeoutSeconds()).thenReturn(30);
            service = new ProductRagAnswerService(knowledge, ChatClient.create(loopback), memory);
            assertTrue(String.join("", answer("我之前码的那些文字还能接着改吗", "wire", 7)
                    .collectList().block(Duration.ofSeconds(35))).contains("卡片"));
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
    @Test void realSpringAiWireRequiresCurrentKnowledgeThenAllowsTheFinalAnswer() throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> wires = new CopyOnWriteArrayList<>();
        ObjectMapper json = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            wires.add(request);
            boolean classification = request.path("messages").get(0).path("content").asText().startsWith("你是用户端客服的请求范围分类器");
            boolean read = !classification && wires.size() == 2;
            Object delta = classification ? Map.of("role", "assistant", "content", "PLATFORM_KNOWLEDGE")
                    : read ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0, "id", "call_current",
                    "type", "function", "function", Map.of("name", "readPlatformHelp", "arguments", "{\"topicTitle\":\"草稿帮助\"}"))))
                    : Map.of("role", "assistant", "content", "草稿仅本人可见。");
            byte[] body = ("data: " + json.writeValueAsString(Map.of("id", "current-evidence-loopback", "object", "chat.completion.chunk",
                    "created", 1, "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", delta,
                    "finish_reason", read ? "tool_calls" : "stop")))) + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            when(knowledge.chatTimeoutSeconds()).thenReturn(30);
            var provider = loopback(server, new ReleaseProviderBudget("", "", 64, 20));
            service = new ProductRagAnswerService(knowledge, ChatClient.create(provider), memory);
            assertEquals("草稿仅本人可见。", String.join("", answer("草稿对谁可见", "required-wire", 7)
                    .collectList().block(Duration.ofSeconds(35))));
            assertEquals(3, wires.size());
            assertFalse(wires.getFirst().hasNonNull("tool_choice"));
            assertEquals("required", wires.get(1).path("tool_choice").asText());
            assertEquals("auto", wires.get(2).path("tool_choice").asText());
            assertEquals("disabled", wires.get(1).path("thinking").path("type").asText());
            assertTrue(wires.get(2).path("messages").toString().contains("草稿仅本人可见"));
            verify(knowledge).retrieveByTitle("草稿帮助");
        } finally { server.stop(0); }
    }

    private OpenAiChatModel loopback(HttpServer server, ReleaseProviderBudget budget) {
        AiConfig config = new AiConfig();
        ReflectionTestUtils.setField(config, "apiKey", "offline-fixture-only");
        ReflectionTestUtils.setField(config, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(config, "completionsPath", "/chat/completions");
        ReflectionTestUtils.setField(config, "model", "fixture");
        ReflectionTestUtils.setField(config, "temperature", 0.0);
        ReflectionTestUtils.setField(config, "maxTokens", 2048);
        return config.openAiChatModel(budget);
    }
}
