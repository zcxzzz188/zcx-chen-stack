package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.constants.AiPromptConstants;
import com.zcx.chenstack.service.CustomerServiceTools;
import com.zcx.chenstack.service.CustomerServiceError;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 模型理解需求并选择工具；工具执行、数据授权和来源生命周期由服务端控制。 */
@lombok.extern.slf4j.Slf4j
public final class ProductRagAnswerService {
    private static final int MAX_MODEL_ROUNDS = 6;
    private static final int MAX_TOPIC_READS = 6;
    private static final int MAX_TOOL_CALLS_PER_ROUND = 8;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RagKnowledgeService knowledge;
    private final ChatClient client;
    private final ChatMemory memory;
    private final CustomerServiceScope scope;
    private final ReleaseProviderBudget budget;
    private final ToolCallingManager toolManager = ToolCallingManager.builder().build();
    private final Set<String> activeConversations = ConcurrentHashMap.newKeySet();

    public ProductRagAnswerService(RagKnowledgeService knowledge, ChatClient client, ChatMemory memory) {
        this(knowledge, client, memory, null);
    }

    public ProductRagAnswerService(RagKnowledgeService knowledge, ChatClient client, ChatMemory memory,
                                   ReleaseProviderBudget budget) {
        this.knowledge = knowledge;
        this.client = client;
        this.memory = memory;
        this.scope = new CustomerServiceScope(client);
        this.budget = budget;
    }

    public Flux<String> answer(String question, String chatId, Integer userId, BooleanSupplier reserveUsage) {
        return answer(question, chatId, userId, reserveUsage, null);
    }

    public Flux<String> answer(String question, String chatId, Integer userId, BooleanSupplier reserveUsage,
                               CustomerServiceTools tools) {
        if (userId == null || userId <= 0 || chatId == null || chatId.isBlank() || chatId.length() > 128
                || question == null || question.isBlank() || question.length() > 1000) {
            return Flux.error(new IllegalArgumentException("客服请求无效"));
        }
        String memoryId = userId + ":" + chatId;
        return Flux.defer(() -> {
            if (!activeConversations.add(memoryId)) {
                return Flux.error(new IllegalStateException("请等待上一条回答完成"));
            }
            long started = System.nanoTime();
            HelpTools help = new HelpTools(tools, question);
            Flux<String> response = Flux.defer(() -> {
                // 分类及首轮正文至少需要两次；后续工具轮仍由每次 HTTP 发送门禁原子扣减。
                if (budget != null) budget.checkChatCapacity(2);
                if (!reserveUsage.getAsBoolean()) return Flux.just("今日AI调用次数已达上限，请明天再试。");
                return scope.permits(question, memory.get(memoryId)).flatMapMany(permitted -> {
                if (!permitted) return Flux.just(CustomerServiceScope.REFUSAL);
                List<Message> messages = new ArrayList<>();
                messages.add(new SystemMessage(AiPromptConstants.CUSTOMER_SERVICE_SYSTEM_PROMPT));
                messages.add(new SystemMessage(help.topicDirectory()));
                if (tools == null) messages.add(new SystemMessage("当前回复通道仅支持文字，不提供导航按钮或文章卡片；说明已核实的页面入口，不承诺在回复中展示按钮或卡片。"));
                messages.addAll(recentHistory(memoryId, help));
                String retained = help.historyContext();
                if (!retained.isBlank()) messages.add(new SystemMessage("以下是本轮重新核验仍有效的帮助正文。请以原始正文为准，历史回复中的推测不是事实依据；新主题或缺少条件时继续检索。\n" + retained));
                messages.add(new UserMessage(question));
                var callbacks = tools == null ? ToolCallbacks.from(help) : ToolCallbacks.from(help, tools);
                OpenAiChatOptions options = OpenAiChatOptions.builder().maxTokens(2048)
                        .internalToolExecutionEnabled(false).toolCallbacks(callbacks).streamUsage(true).build();
                return round(messages, options, help, 0);
                });
            });
            return remember(memoryId, question, RagAnswerLinks.sanitize(response), help)
                    .timeout(Duration.ofSeconds(knowledge.chatTimeoutSeconds()))
                    .doFinally(signal -> {
                        if (tools != null) tools.discardPending();
                        activeConversations.remove(memoryId);
                        log.info("xiaochen terminal={} millis={}", signal,
                                Duration.ofNanos(System.nanoTime() - started).toMillis());
                    });
        });
    }

    private List<Message> recentHistory(String memoryId, HelpTools help) {
        List<Message> history = memory.get(memoryId);
        List<Message> recent = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0 && recent.size() < 16; i--) {
            Message message = history.get(i);
            if (!(message instanceof UserMessage || message instanceof AssistantMessage)) continue;
            if (message instanceof AssistantMessage && !help.retainHistory(message)) continue;
            chars += message.getText().length();
            if (chars > 8000) break;
            // 来源对象只留在服务端记忆，不能序列化到模型请求。
            recent.addFirst(message instanceof AssistantMessage ? new AssistantMessage(message.getText()) : message);
        }
        return recent;
    }

    private Flux<String> round(List<Message> messages, OpenAiChatOptions options, HelpTools help, int round) {
        return Flux.defer(() -> {
            help.requireCurrent();
            AtomicReference<ChatResponse> complete = new AtomicReference<>();
            Flux<ChatResponse> stream = client.prompt().messages(messages).options(options)
                    .templateRenderer(new NoOpTemplateRenderer()).stream().chatResponse();
            // 每轮完成后才释放正文，避免展示工具调用前的猜测、截断或未执行承诺。
            return new MessageAggregator().aggregate(stream, complete::set).thenMany(Flux.defer(() -> {
                ChatResponse reply = complete.get();
                if (reply == null || reply.getResult() == null) return Flux.error(CustomerServiceError.SSE_INCOMPLETE.exception());
                var usage = reply.getMetadata().getUsage();
                log.info("xiaochen model_round={} input_tokens={} output_tokens={}", round + 1,
                        usage.getPromptTokens(), usage.getCompletionTokens());
                String finish = reply.getResult().getMetadata().getFinishReason();
                if (reply.hasToolCalls()) {
                    if (!"tool_calls".equalsIgnoreCase(finish) || round >= MAX_MODEL_ROUNDS - 1
                            || reply.getResult().getOutput().getToolCalls().size() > MAX_TOOL_CALLS_PER_ROUND) {
                        return Flux.error(CustomerServiceError.SSE_INCOMPLETE.exception());
                    }
                    log.info("xiaochen tools={}", reply.getResult().getOutput().getToolCalls().stream()
                            .map(AssistantMessage.ToolCall::name).toList());
                    if (help.requestsBlockedTopic(reply.getResult().getOutput().getToolCalls())) {
                        if (help.events != null) help.events.discardPending();
                        return Flux.just(CustomerServiceScope.REFUSAL);
                    }
                    return Mono.fromCallable(() -> toolManager.executeToolCalls(new Prompt(messages, options), reply))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMapMany(result -> {
                                // 工具轮正文未展示，后续上下文不能让模型误以为用户已经读过。
                                List<Message> next = result.conversationHistory().stream().map(message -> {
                                    if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                                        return (Message) AssistantMessage.builder().content("")
                                                .toolCalls(assistant.getToolCalls()).properties(assistant.getMetadata()).build();
                                    }
                                    return message;
                                }).toList();
                                return round(next, options, help, round + 1);
                            });
                }
                if (!"stop".equalsIgnoreCase(finish)) return Flux.error(CustomerServiceError.SSE_INCOMPLETE.exception());
                String text = reply.getResult().getOutput().getText();
                if (text != null && CustomerServiceTools.promisesNavigation(text)) {
                    if (help.events == null) return Flux.error(new IllegalStateException("当前通道不能展示导航按钮"));
                    help.events.ensureReplyActions(text);
                }
                return text == null || text.isBlank() ? Flux.error(CustomerServiceError.SSE_INCOMPLETE.exception())
                        : help.guard(Flux.just(text));
            }));
        });
    }

    /** 请求内独立工具；只向模型提供已核验公开资料，不开放目录或数据库。 */
    public final class HelpTools {
        private final CustomerServiceTools events;
        private final String originalQuestion;
        private final Map<String, KnowledgeEvidence> evidence = new LinkedHashMap<>();
        private final Set<KnowledgeCatalog> snapshots = new LinkedHashSet<>();
        private final List<KnowledgeEvidence> historyEvidence = new ArrayList<>();
        private final Set<String> offeredTopics = new LinkedHashSet<>();
        private final Set<String> blockedTopics = new LinkedHashSet<>();
        private int topicReads;

        HelpTools(CustomerServiceTools events, String originalQuestion) {
            this.events = events;
            this.originalQuestion = originalQuestion;
        }

        private String offerTopics(KnowledgeCatalog catalog, boolean withKeywords) {
            List<String> topics = new ArrayList<>();
            for (String id : catalog.sourceIds()) {
                KnowledgeDocument source = catalog.source(id);
                if (!"PUBLIC".equals(source.visibility()) || !"DOCUMENT_REVIEWED".equals(source.reviewStatus())) continue;
                if (!CustomerServiceScope.permitsSource(id)) {
                    blockedTopics.add(source.title());
                    continue;
                }
                topics.add(source.title() + (withKeywords && source.keywords() != null && !source.keywords().isEmpty()
                        ? "（主题词：" + String.join("、", source.keywords()) + "）" : ""));
                offeredTopics.add(source.title());
            }
            return String.join("；", topics);
        }

        String topicDirectory() {
            try {
                KnowledgeCatalog catalog = knowledge.reviewedCatalog();
                snapshots.add(catalog);
                return "当前公开已审校帮助主题目录（标题和主题词仅用于选择，不是功能事实）：" + offerTopics(catalog, true)
                        + "\n理解用户完整目标后，可直接调用readPlatformHelp读取相符主题全文，不必先做语义检索。"
                        + "需要查找目录外表达或跨主题内容时使用searchPlatformHelp。必须取得相应正文才解释功能或限制。";
            } catch (RagFailure unavailable) {
                log.warn("xiaochen directory unavailable: {}", unavailable.code());
                if (events != null) events.knowledgeUnavailable();
                return "当前已审核帮助目录暂不可用，不能据此推断任何平台功能存在或不存在。";
            }
        }

        private KnowledgeEvidence customerEvidence(KnowledgeEvidence found) {
            return new KnowledgeEvidence(found.chunks().stream()
                    .filter(chunk -> CustomerServiceScope.permitsSource(chunk.getMetadata().get("source_id"))).toList(), found.catalog());
        }

        boolean requestsBlockedTopic(List<AssistantMessage.ToolCall> calls) {
            for (AssistantMessage.ToolCall call : calls) {
                if (!"readPlatformHelp".equals(call.name())) continue;
                try {
                    if (blockedTopics.contains(JSON.readTree(call.arguments()).path("topicTitle").asText())) return true;
                } catch (JsonProcessingException invalidArguments) {
                    // 无效参数交给现有工具校验处理，不把它当作有效主题。
                }
            }
            return false;
        }

        private String context(KnowledgeEvidence found) {
            String topics = offerTopics(found.catalog(), false);
            return "用户当前原问题（仅用于理解意图，不是事实依据）：" + originalQuestion
                    + "\n本次已召回资料正文：" + found.context()
                    + "\n可读取的已审核帮助主题（标题不能代替正文事实）：" + topics
                    + "\n请核对资料是否回答原问题。主题不符、无命中或缺少关键资料时，调用readPlatformHelp读取对应的精确主题标题；不能套用其他功能的规则。";
        }

        @Tool(description = "按语义查询辰栈当前公开且已审校的帮助资料。目录中已有明确对应主题时可直接readPlatformHelp，不必先检索。将多轮指代改写成独立完整的问题。资料无命中不代表功能不存在。")
        public String searchPlatformHelp(@ToolParam(description = "结合上下文改写的完整辰栈使用问题，须保留原问题的业务对象和任务，不能用其他功能替代；1至1000字") String query) {
            if (query == null || query.isBlank() || query.length() > 1000) return "查询问题无效，请给出完整的使用问题。";
            String key = "query:" + query;
            if (evidence.containsKey(key)) return context(evidence.get(key));
            if (evidence.keySet().stream().filter(entry -> entry.startsWith("query:")).count() >= 3) return "本轮已经查询过多项资料，请根据已有资料回答或请用户缩小范围。";
            try {
                KnowledgeEvidence found = customerEvidence(knowledge.retrieve(query));
                evidence.put(key, found);
                snapshots.add(found.catalog());
                boolean anchored = knowledge.hasTopicAnchor(found, originalQuestion);
                if (events != null) events.knowledgeStatus(anchored ? "SUPPORTED" : "UNKNOWN");
                return (found.chunks().isEmpty()
                        ? "本次语义查询没有匹配资料。请从已审核主题选择对应标题调用readPlatformHelp；仍缺少资料才说明不能确认，不推断功能不存在。\n"
                        : anchored ? "" : "本次只有语义候选，尚未确认与原问题为同一业务对象。请补读相符主题；用户所说对象仍不唯一时先澄清，不能把候选功能认定为用户正在问的功能。\n") + context(found);
            } catch (RagFailure error) {
                log.warn("xiaochen knowledge unavailable: {}", error.code());
                if (events != null) events.knowledgeUnavailable();
                return "语义查询暂时不可用，请从当前已审核目录选择相符主题调用readPlatformHelp；必须取得正文才能确认规则。本人文章查询和页面引导工具仍可使用。";
            }
        }

        @Tool(description = "从当前已审核帮助目录选择与用户目标相符的精确主题标题，读取完整正文。可以直接调用，无需先做语义查询；也用于补足查询遗漏的相关规则。标题不能代替正文事实，不接受URL或自造标题。")
        public String readPlatformHelp(@ToolParam(description = "从工具给出的已审核帮助主题中选择一个精确标题，保留全部文字；1至80字") String topicTitle) {
            if (blockedTopics.contains(topicTitle)) return CustomerServiceScope.REFUSAL;
            if (topicTitle == null || topicTitle.isBlank() || topicTitle.length() > 80 || !offeredTopics.contains(topicTitle)) {
                return "请选择当前已审核目录中的精确帮助主题标题；目录不可用时不能自造标题或推断正文。";
            }
            try {
                KnowledgeEvidence found = readTopic(topicTitle);
                // 私信发送、撤回和会话管理是同一能力的互补正文，不能用会话页穷举全部私信功能。
                if (!found.chunks().isEmpty() && (originalQuestion.contains("私信") || found.chunks().stream()
                        .anyMatch(chunk -> String.valueOf(chunk.getMetadata().get("source_id")).startsWith("help-private-")))) {
                    Map<String, Document> chunks = new LinkedHashMap<>();
                    found.chunks().forEach(chunk -> chunks.putIfAbsent(chunk.getId(), chunk));
                    for (String id : found.catalog().sourceIds()) {
                        KnowledgeDocument source = found.catalog().source(id);
                        if (!id.startsWith("help-private-") || !offeredTopics.contains(source.title())) continue;
                        KnowledgeEvidence related = readTopic(source.title());
                        if (related.catalog() != found.catalog() || related.chunks().isEmpty()) {
                            throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                        }
                        related.chunks().forEach(chunk -> chunks.putIfAbsent(chunk.getId(), chunk));
                    }
                    found = new KnowledgeEvidence(List.copyOf(chunks.values()), found.catalog());
                    if (found.context().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > knowledge.contextByteBudget()) {
                        return "私信相关资料超出本轮正文预算，请缩小问题；已读单篇不能代表全部私信功能。";
                    }
                }
                if (events != null) events.knowledgeStatus(found.chunks().isEmpty() ? "UNKNOWN" : "SUPPORTED");
                return context(found);
            } catch (RagFailure error) {
                log.warn("xiaochen topic unavailable: {}", error.code());
                if (events != null) events.knowledgeUnavailable();
                return "该主题正文暂时不可用，不能仅凭标题推断规则；请依据其他已核实资料回答。";
            }
        }

        private KnowledgeEvidence readTopic(String title) {
            String key = "topic:" + title;
            if (evidence.containsKey(key)) return evidence.get(key);
            if (topicReads >= MAX_TOPIC_READS) throw new RagFailure(RagFailure.Code.COST_LIMIT);
            topicReads++;
            KnowledgeEvidence found = customerEvidence(knowledge.retrieveByTitle(title));
            evidence.put(key, found);
            snapshots.add(found.catalog());
            return found;
        }

        boolean retainHistory(Message message) {
            if (!"USER_FRONTEND".equals(message.getMetadata().get("customerServiceScope"))) return false;
            Object bound = message.getMetadata().get("knowledgeSnapshots");
            if (!(bound instanceof List<?> entries)) return true;
            List<KnowledgeCatalog> current = new ArrayList<>();
            try {
                for (Object entry : entries) {
                    if (!(entry instanceof KnowledgeCatalog snapshot)) return false;
                    snapshot.requireCurrent();
                    current.add(snapshot);
                }
            } catch (RuntimeException unavailable) {
                return false;
            }
            if (!(message.getMetadata().get("knowledgeEvidence") instanceof List<?> facts)) return false;
            List<KnowledgeEvidence> retained = new ArrayList<>();
            for (Object fact : facts) {
                if (!(fact instanceof KnowledgeEvidence entry) || !current.contains(entry.catalog())
                        || entry.chunks().stream().anyMatch(chunk -> !CustomerServiceScope.permitsSource(chunk.getMetadata().get("source_id")))) return false;
                retained.add(entry);
            }
            if (historyEvidence.isEmpty()) historyEvidence.addAll(retained);
            snapshots.addAll(current);
            return true;
        }

        String historyContext() {
            StringBuilder context = new StringBuilder();
            for (KnowledgeEvidence entry : historyEvidence.reversed()) {
                String part = entry.context();
                String next = context + "\n" + part;
                if (next.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= knowledge.contextByteBudget()) {
                    context.append('\n').append(part);
                }
            }
            return context.toString();
        }

        void requireCurrent() { snapshots.forEach(KnowledgeCatalog::requireCurrent); }

        Flux<String> guard(Flux<String> response) {
            for (KnowledgeCatalog snapshot : snapshots) response = snapshot.lifecycle().guard(snapshot, response);
            return response;
        }
    }

    private Flux<String> remember(String memoryId, String question, Flux<String> response, HelpTools help) {
        return Flux.defer(() -> {
            StringBuilder complete = new StringBuilder();
            return response.doOnNext(part -> {
                if (complete.length() + part.length() > 32768) throw new IllegalStateException("客服回答超出长度限制");
                complete.append(part);
            }).concatWith(Flux.defer(() -> {
                if (complete.toString().isBlank()) return Flux.error(new IllegalStateException("客服未返回有效正文"));
                memory.add(memoryId, List.of(new UserMessage(question), AssistantMessage.builder()
                        .content(complete.toString()).properties(Map.of("customerServiceScope", "USER_FRONTEND", "knowledgeSnapshots", List.copyOf(help.snapshots),
                                "knowledgeEvidence", help.evidence.isEmpty() ? List.copyOf(help.historyEvidence)
                                        : List.copyOf(help.evidence.values()))).build()));
                if (help.events != null) {
                    if (CustomerServiceScope.REFUSAL.contentEquals(complete)) help.events.discardPending();
                    else help.events.publishPending();
                }
                return Flux.empty();
            }));
        });
    }
}
