package com.zcx.chenstack.rag;

import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 当前资源目录是权威依据；向量库内容只用于定位，不能自行成为权威知识。 */
public final class KnowledgeCatalog {
    private static final Set<String> HELP_ROUTES = Set.of("/", "/register", "/login", "/reset", "/search", "/editor", "/creation/articlemanage", "/article", "/setting");
    private final Map<String, KnowledgeDocument> sources;
    private final Map<String, Document> chunks;

    public KnowledgeCatalog(List<KnowledgeDocument> documents) {
        Map<String, KnowledgeDocument> sourceMap = new LinkedHashMap<>();
        Map<String, Document> chunkMap = new LinkedHashMap<>();
        for (KnowledgeDocument source : documents) {
            if (source.sourceId() == null || !source.sourceId().matches("[a-z0-9_-]{1,80}")
                    || source.version() == null || !source.version().matches("[a-zA-Z0-9._-]{1,40}")
                    || source.text() == null || source.text().isBlank() || source.text().length() > 20000
                    || source.title() == null || source.title().isBlank() || source.title().length() > 80
                    || source.title().codePoints().anyMatch(Character::isISOControl)
                    || !"PLATFORM_HELP".equals(source.sourceType()) || source.updatedAt() == null
                    || !HELP_ROUTES.contains(source.sourceUrl()) || sourceMap.putIfAbsent(source.sourceId(), source) != null) {
                throw new IllegalArgumentException("帮助知识来源无效或重复");
            }
            if (!"PUBLIC".equals(source.visibility()) || !"DOCUMENT_REVIEWED".equals(source.reviewStatus())) continue;
            if (source.keywords() != null && (source.keywords().size() > 20
                    || source.keywords().stream().anyMatch(k -> k == null || k.isBlank() || k.length() > 30))) {
                throw new IllegalArgumentException("帮助关键词超出限制");
            }
            String text = normalize(source.text());
            String hash = hash(text);
            int ordinal = 0;
            for (int start = 0; start < text.length(); start += 320) {
                int end = Math.min(start + 400, text.length());
                // UTF-16 分块不得拆开 Unicode 代理对。
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                int actualStart = start > 0 && Character.isLowSurrogate(text.charAt(start)) ? start - 1 : start;
                String id = UUID.nameUUIDFromBytes((source.sourceId() + ":" + source.version() + ":" + hash
                        + ":chunk-v1:" + ordinal++).getBytes(StandardCharsets.UTF_8)).toString();
                Map<String, Object> metadata = Map.of("source_id", source.sourceId(), "source_type", source.sourceType(),
                        "version", source.version(), "visibility", source.visibility(), "review_status", source.reviewStatus(),
                        "updated_at", source.updatedAt().toString(), "source_url", source.sourceUrl(),
                        "content_hash", hash, "chunk_id", id);
                chunkMap.put(id, new Document(id, text.substring(actualStart, end), metadata));
                if (end == text.length()) break;
            }
        }
        sources = Collections.unmodifiableMap(sourceMap);
        chunks = Collections.unmodifiableMap(chunkMap);
    }

    public List<Document> chunks() { return List.copyOf(chunks.values()); }
    public Set<String> sourceIds() { return sources.keySet(); }
    public KnowledgeDocument source(String id) { return sources.get(id); }

    public Document verified(Document candidate) {
        Document current = chunks.get(candidate.getId());
        if (current == null) return null;
        for (Map.Entry<String, Object> field : current.getMetadata().entrySet()) {
            if (!field.getValue().equals(candidate.getMetadata().get(field.getKey()))) return null;
        }
        // 不采信向量库的正文，即使 payload 被篡改也只能取本地已审校版本。
        return current;
    }

    public int keywordScore(Document chunk, String query) {
        KnowledgeDocument source = sources.get(chunk.getMetadata().get("source_id"));
        if (source.keywords() == null) return 0;
        return (int) source.keywords().stream().filter(query::contains).count();
    }

    public static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n').strip(); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
}
