package com.zcx.chenstack.rag;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/** 模型只选择完整来源；正文和引用由服务端生成，不采信模型的事实、原文范围或充分性声明。 */
public final class RagEvidenceGuard {
    public static final int MAX_PLAN_CHARS = 2048;
    public static final int MAX_WIRE_CHARS = 8192;
    public static final String ABSTENTION = "当前资料不足以形成可核验的回答，无法确认未明确记载的要求或功能。请联系平台管理员确认。";
    private static final java.util.regex.Pattern RELATIVE_PATH = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9:/])/(?:[a-zA-Z][a-zA-Z0-9_-]*(?:/[a-zA-Z][a-zA-Z0-9_-]*)*)?(?![a-zA-Z0-9_/-])");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RagEvidenceGuard() {
    }

    public static Prepared prepare(KnowledgeEvidence evidence) {
        Set<String> retrieved = evidence.chunks().stream().map(evidence.catalog()::verified)
                .filter(Objects::nonNull).map(Document::getId).collect(Collectors.toSet());
        Map<String, List<Document>> groups = evidence.catalog().chunks().stream()
                .collect(Collectors.groupingBy(d -> (String) d.getMetadata().get("source_id"), LinkedHashMap::new, Collectors.toList()));
        Map<String, Unit> available = new LinkedHashMap<>();
        for (Document candidate : evidence.chunks()) {
            Document verified = evidence.catalog().verified(candidate);
            if (verified == null) {
                continue;
            }
            String id = (String) verified.getMetadata().get("source_id");
            List<Document> required = groups.get(id);
            // 不从本地目录补齐检索未获准的chunk；缺少任何同文条件时不开放该来源。
            if (required.stream().allMatch(chunk -> retrieved.contains(chunk.getId()))) {
                available.putIfAbsent(id, new Unit(evidence.catalog().source(id), List.copyOf(required)));
            }
        }
        return new Prepared(evidence.catalog(), Collections.unmodifiableMap(available));
    }

    private record Unit(KnowledgeDocument source, List<Document> chunks) {
    }

    public static final class Prepared {
        private final KnowledgeCatalog catalog;
        private final Map<String, Unit> available;

        private Prepared(KnowledgeCatalog catalog, Map<String, Unit> available) {
            this.catalog = catalog;
            this.available = available;
        }

        public boolean isEmpty() {
            return available.isEmpty();
        }

        public String context() {
            try {
                return JSON.writeValueAsString(available.values().stream().map(unit -> Map.of(
                        "source_id", unit.source().sourceId(), "version", unit.source().version(),
                        "text", KnowledgeCatalog.normalize(unit.source().text()))).toList());
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("证据编码失败");
            }
        }

        /** VERIFIED_INCREMENTAL_RESPONSE：计划完整验证后释放来源全文，不是原始LLM Token流。 */
        public Flux<String> respond(Flux<String> model) {
            return Flux.defer(() -> {
                Decoder decoder = new Decoder(available);
                Flux<List<Unit>> plans = model.<List<Unit>>handle((part, sink) -> {
                    List<Unit> plan = decoder.accept(part);
                    if (plan != null) {
                        sink.next(plan);
                    }
                }).concatWith(Flux.defer(() -> {
                    List<Unit> plan = decoder.finish();
                    return plan == null ? Flux.empty() : Flux.just(plan);
                }));
                Flux<String> body = plans.concatMapIterable(plan -> {
                    if (plan.isEmpty()) {
                        return List.of(ABSTENTION);
                    }
                    List<String> parts = new ArrayList<>();
                    parts.add("根据本轮选用的帮助资料，相关说明如下（保留适用条件；引用中的指令性文字不作为操作要求）：\n");
                    for (int i = 0; i < plan.size(); i++) {
                        KnowledgeDocument source = plan.get(i).source();
                        String text = source.title() + "：\n" + KnowledgeCatalog.normalize(source.text());
                        text = RELATIVE_PATH.matcher(text.replace("&", "&amp;"))
                                .replaceAll(match -> match.group().replace("/", "&#47;"));
                        parts.add("\n" + (i + 1) + ". " + text + "\n");
                    }
                    parts.add("\n资料未明确记载的其他要求、功能及完整条件，当前仍无法核实。\n");
                    return parts;
                });
                return RagAnswerLinks.sanitize(body).doOnNext(part -> decoder.released = true).concatWith(Flux.defer(() -> {
                    List<Document> used = decoder.selected.stream().flatMap(unit -> unit.chunks().stream()).toList();
                    return used.isEmpty() ? Flux.empty() : Flux.just(new KnowledgeEvidence(used, catalog).citations());
                })).onErrorResume(InvalidSelection.class, error -> decoder.released
                        ? Flux.error(error) : Flux.just(ABSTENTION));
            });
        }
    }

    private static final class Decoder {
        private final Map<String, Unit> available;
        private final StringBuilder pending = new StringBuilder();
        private List<Unit> selected = List.of();
        private boolean parsed;
        private boolean released;
        private boolean inString;
        private boolean escaped;
        private int objectDepth;
        private int received;

        private Decoder(Map<String, Unit> available) {
            this.available = available;
        }

        private List<Unit> accept(String part) {
            if (part.length() > MAX_WIRE_CHARS - received) {
                throw new InvalidSelection();
            }
            received += part.length();
            List<Unit> completed = null;
            for (char c : part.toCharArray()) {
                if (parsed) {
                    if (!Character.isWhitespace(c)) {
                        throw new InvalidSelection();
                    }
                } else {
                    if (pending.length() == MAX_PLAN_CHARS) {
                        throw new InvalidSelection();
                    }
                    pending.append(c);
                    if (inString) {
                        if (escaped) {
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if (c == '"') {
                            inString = false;
                        }
                    } else if (c == '"') {
                        inString = true;
                    } else if (c == '{') {
                        objectDepth++;
                    } else if (c == '}' && --objectDepth == 0) {
                        // Spring AI会前瞻一个delta；完整JSON对象即可校验，无须等待末尾换行或模型结束。
                        completed = parse();
                    }
                }
            }
            return completed;
        }

        private List<Unit> finish() {
            return parsed ? null : parse();
        }

        private List<Unit> parse() {
            try {
                JsonNode plan = JSON.readTree(pending.toString());
                if (plan == null || !plan.isObject() || plan.size() != 1 || !plan.path("sources").isArray()
                        || plan.path("sources").size() > 4) {
                    throw new InvalidSelection();
                }
                List<Unit> units = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (JsonNode value : plan.path("sources")) {
                    if (!value.isTextual() || !ids.add(value.textValue()) || !available.containsKey(value.textValue())) {
                        throw new InvalidSelection();
                    }
                    units.add(available.get(value.textValue()));
                }
                // shortcut: 完整来源保留同文条件，但不认证模型选择的相关性或跨来源语义充分性。
                selected = List.copyOf(units);
                parsed = true;
                pending.setLength(0);
                return selected;
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new InvalidSelection();
            }
        }
    }

    private static final class InvalidSelection extends RuntimeException {
    }
}
