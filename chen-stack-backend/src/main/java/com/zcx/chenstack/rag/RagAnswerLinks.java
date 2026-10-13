package com.zcx.chenstack.rag;

import reactor.core.publisher.Flux;

import java.util.List;

/** 过滤URL和保留来源标记；普通模型文本不具备来源认证，状态仅属于本次订阅。 */
public final class RagAnswerLinks {
    private static final List<String> PREFIXES = List.of("https://", "http://", "ftp://", "www.", "javascript:", "data:", "mailto:", "/");
    private static final List<String> SOURCE_MARKERS = List.of(KnowledgeEvidence.SOURCE_MARKER, "已核实来源(服务端)");
    private final StringBuilder pending = new StringBuilder();
    private boolean inLink;

    private String accept(String chunk) {
        StringBuilder output = new StringBuilder();
        for (char c : chunk.toCharArray()) {
            if (inLink) {
                if (Character.isWhitespace(c) || "，。；！？」）>)]}".indexOf(c) >= 0) {
                    inLink = false;
                    output.append(c);
                }
                continue;
            }
            pending.append(c);
            while (!pending.isEmpty()) {
                String current = pending.toString().toLowerCase(java.util.Locale.ROOT);
                if (SOURCE_MARKERS.contains(current)) {
                    pending.setLength(0); output.append("[未经核实的模型来源声明]"); break;
                }
                if (PREFIXES.contains(current)) {
                    pending.setLength(0); inLink = true; output.append("[链接以文末来源为准]"); break;
                }
                if (PREFIXES.stream().anyMatch(p -> p.startsWith(current)) || SOURCE_MARKERS.stream().anyMatch(p -> p.startsWith(current))) break;
                output.append(pending.charAt(0)); pending.deleteCharAt(0);
            }
        }
        // 模型文本中的链接语法只能显示为文字，避免编码URL和未知协议绕过前缀过滤。
        // shortcut: RAG代码块内这些字符可能显示为实体，需原文复制时改用独立正文和来源通道。
        return output.toString().replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "&#91;").replace("]", "&#93;");
    }

    public static Flux<String> sanitize(Flux<String> source) {
        return Flux.defer(() -> {
            RagAnswerLinks filter = new RagAnswerLinks();
            return source.map(filter::accept).filter(s -> !s.isEmpty())
                    .concatWith(Flux.defer(() -> filter.pending.isEmpty() ? Flux.empty() : Flux.just(filter.pending.toString())));
        });
    }
}
