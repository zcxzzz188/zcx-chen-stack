package com.zcx.chenstack.wiki;

import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class WikiDraftSummary {
    static final int MAX_LENGTH = 500;
    private static final int TARGET_LENGTH = 150;
    private static final Pattern CONTENT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern SENTENCES = Pattern.compile("(?<=[。！？；])|(?<=[.!?;])\\s+");
    private static final Pattern CLAIM_SUFFIX = Pattern.compile(
            "\\s+\\[SEMANTIC_NOT_VERIFIED\\](?: \\[f\\d+(?:, f\\d+)*\\])?$");

    private WikiDraftSummary() {}

    record Content(boolean hasContent, String summary) {}

    static String title(String markdown) {
        return WikiPublicationService.publicMarkdown(markdown).lines()
                .filter(line -> line.startsWith("# ") || line.startsWith("## "))
                .map(line -> plain(line.substring(line.indexOf(' ') + 1)))
                .filter(line -> meaningful(line) && line.length() <= 160)
                .findFirst().orElse(null);
    }

    static String extract(String body) {
        return content(body).summary();
    }

    static Content content(String body) {
        List<String> summaries = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        boolean summary = false;
        boolean evidence = false;
        boolean related = false;
        String fence = null;
        for (String raw : body.lines().toList()) {
            String line = raw.strip();
            if (line.startsWith("```") || line.startsWith("~~~")) {
                String marker = line.substring(0, 3);
                if (fence == null || fence.equals(marker)) {
                    fence = fence == null ? marker : null;
                }
                continue;
            }
            if (fence != null || line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                summary = line.equals("### 主题摘要");
                evidence = line.equals("### 关键事实与证据");
                related = line.equals("### 相关主题");
                continue;
            }
            if (related || line.startsWith(">") || line.startsWith("主题标识：") || line.startsWith("- snapshot=")
                    || line.endsWith(" [UNSUPPORTED：待人工核查]") || (evidence && line.contains("CONFLICT_CANDIDATE"))) {
                continue;
            }
            String content = plain(CLAIM_SUFFIX.matcher(line).replaceFirst("")
                    .replaceFirst("^(?:[-+*]|\\d+[.)])\\s+", ""));
            if (meaningful(content)) {
                (summary ? summaries : evidence ? facts : paragraphs).add(content);
            }
        }
        String overview = excerpt(summaries);
        if (overview == null) {
            overview = excerpt(paragraphs);
        }
        if (overview == null) {
            overview = excerpt(facts);
        }
        return new Content(!summaries.isEmpty() || !paragraphs.isEmpty() || !facts.isEmpty(), overview);
    }

    private static String excerpt(List<String> lines) {
        StringBuilder result = new StringBuilder();
        String longer = null;
        for (String line : lines) {
            for (String sentence : SENTENCES.split(line)) {
                String text = sentence.strip();
                if (!meaningful(text)) {
                    continue;
                }
                if (text.length() > TARGET_LENGTH) {
                    if (longer == null && text.length() <= MAX_LENGTH) {
                        longer = text;
                    }
                    continue;
                }
                if (result.length() + 1 + text.length() > TARGET_LENGTH && !result.isEmpty()) {
                    return result.toString();
                }
                if (!result.isEmpty()) {
                    result.append(' ');
                }
                result.append(text);
            }
        }
        return result.isEmpty() ? longer : result.toString();
    }

    static boolean meaningful(String text) {
        return text != null && text.indexOf('\0') < 0 && CONTENT.matcher(text).find();
    }

    private static String plain(String line) {
        String text = line.replaceAll("!?\\[([^\\[\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]*>", "")
                .replaceAll("\\\\([\\\\\\[\\]*`])", "$1");
        return Parser.unescapeEntities(text, false).strip();
    }
}
