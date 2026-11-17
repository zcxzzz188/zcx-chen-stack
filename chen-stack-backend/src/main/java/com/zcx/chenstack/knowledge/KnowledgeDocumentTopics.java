package com.zcx.chenstack.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

final class KnowledgeDocumentTopics {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+)$");
    private static final Pattern OUTLINE = Pattern.compile("^(?:[一二三四五六七八九十]+、|第[一二三四五六七八九十0-9]+章[：:、 ])([^。！？；]{1,80})$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}([`]{3,}|~{3,})(.*)$");
    private static final Pattern ANCHOR = Pattern.compile("<!-- knowledge:([1-9][0-9]{0,17}|new) -->");
    private static final int MAX_TOPICS = 32;

    private KnowledgeDocumentTopics() {}

    record Topic(Long id, String title, String content) {}
    record Document(String title, List<Topic> topics) {}
    private record Heading(int line, int level, String title) {}

    static Document parse(String filename, byte[] bytes, boolean markdown, KnowledgeWikiGenerator generator) {
        if (bytes == null || bytes.length == 0 || bytes.length > KnowledgeTextParser.MAX_FILE_BYTES) {
            throw invalid("文件不能为空或超过 2 MiB");
        }
        int offset = KnowledgeTextParser.startsWithBom(bytes) ? 3 : 0;
        String content = KnowledgeChunker.normalize(KnowledgeTextParser.decodeUtf8(Arrays.copyOfRange(bytes, offset, bytes.length)));
        if (content.isEmpty() || content.indexOf(0) >= 0) {
            throw invalid("文件须为非空 UTF-8 文本");
        }
        String title = KnowledgeTextParser.resolveTitle(null, content, filename);
        List<String> lines = Arrays.asList(content.split("\\n", -1));
        List<Heading> headings = headings(lines, markdown);
        boolean anchored = markdown && headings.stream().anyMatch(heading -> isAnchored(lines, heading));
        boolean root = markdown && !headings.isEmpty() && headings.getFirst().line() == 0
                && (anchored || headings.stream().filter(heading -> heading.level() == 1).count() == 1)
                && headings.getFirst().level() == 1;
        List<Heading> candidates = root ? headings.subList(1, headings.size()) : headings;
        int level = candidates.stream().mapToInt(Heading::level).min().orElse(0);
        List<Heading> topics = candidates.stream().filter(heading -> anchored
                ? isAnchored(lines, heading) : heading.level() == level).toList();
        List<Topic> result = new ArrayList<>();
        if (!topics.isEmpty() && (markdown || topics.size() > 1)) {
            int start = root ? 1 : 0;
            String introduction = join(lines, start, topics.getFirst().line());
            if (!introduction.isBlank()) {
                result.add(new Topic(null, title, introduction));
            }
            for (int i = 0; i < topics.size(); i++) {
                Heading heading = topics.get(i);
                int bodyStart = heading.line() + 1;
                int end = i + 1 == topics.size() ? lines.size() : topics.get(i + 1).line();
                var marker = ANCHOR.matcher(bodyStart < end ? lines.get(bodyStart) : "");
                boolean hasMarker = marker.matches();
                Long id = hasMarker && !"new".equals(marker.group(1)) ? Long.parseLong(marker.group(1)) : null;
                String body = join(lines, hasMarker ? bodyStart + 1 : bodyStart, end);
                result.add(new Topic(id, heading.title(), body.isBlank() && id == null ? lines.get(heading.line()) : body));
            }
        } else {
            List<String> paragraphs = List.of(content.split("\\n[ \\t]*\\n+", -1));
            result.addAll(generator == null ? List.of() : generator.split(paragraphs));
            if (result.isEmpty()) {
                result.add(new Topic(null, title, content));
            }
        }
        validate(result);
        return new Document(title, List.copyOf(result));
    }

    private static boolean isAnchored(List<String> lines, Heading heading) {
        return heading.level() == 2 && heading.line() + 1 < lines.size()
                && ANCHOR.matcher(lines.get(heading.line() + 1)).matches();
    }

    private static List<Heading> headings(List<String> lines, boolean markdown) {
        List<Heading> result = new ArrayList<>();
        String fence = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            var boundary = FENCE.matcher(line);
            if (markdown && boundary.matches()) {
                String marker = boundary.group(1);
                if (fence == null) {
                    fence = marker;
                } else if (marker.startsWith(fence) && boundary.group(2).isBlank()) {
                    fence = null;
                }
                continue;
            }
            if (fence != null) {
                continue;
            }
            var heading = (markdown ? HEADING : OUTLINE).matcher(line);
            if (heading.matches()) {
                int level = markdown ? heading.group(1).length() : 2;
                String title = markdown ? heading.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "") : heading.group(1);
                result.add(new Heading(i, level, title.strip()));
            }
        }
        return result;
    }

    private static String join(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end)).strip();
    }

    static void validate(List<Topic> topics) {
        var titles = new HashSet<String>();
        var ids = new HashSet<Long>();
        if (topics.isEmpty() || topics.size() > MAX_TOPICS) {
            throw invalid("一份文档应包含 1 至 32 个知识主题");
        }
        for (Topic topic : topics) {
            if (topic.title() == null || topic.title().isBlank() || topic.title().length() > 160
                    || topic.title().chars().anyMatch(Character::isISOControl) || !titles.add(topic.title())
                    || (topic.id() != null && !ids.add(topic.id())) || topic.content().isBlank()
                    || topic.content().length() > KnowledgeChunker.MAX_DOCUMENT_CHARS) {
                throw invalid("章节标题须唯一，正文须非空且每章不超过两万字");
            }
        }
    }

    static byte[] render(String title, List<Topic> topics) {
        validate(topics);
        StringBuilder result = new StringBuilder("# ").append(title).append("\n\n");
        for (Topic topic : topics) {
            result.append("## ").append(topic.title()).append("\n<!-- knowledge:").append(topic.id())
                    .append(" -->\n\n").append(topic.content()).append("\n\n");
        }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > KnowledgeTextParser.MAX_FILE_BYTES) {
            throw invalid("整理母版不能超过 2 MiB");
        }
        return bytes;
    }

    private static KnowledgeDocException invalid(String message) {
        return KnowledgeDocException.bad("KNOWLEDGE_DOCUMENT_TOPICS", message);
    }
}
