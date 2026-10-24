package com.zcx.chenstack.rag;

import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 不可变内容快照；授权有效性由所属生命周期管理器决定，向量只用于定位。 */
public final class KnowledgeCatalog {
    private static final Set<String> HELP_ROUTES = Set.of("/", "/register", "/login", "/reset", "/search", "/editor", "/creation/articlemanage", "/creation/columnmanage", "/article", "/setting");
    private final Map<String, KnowledgeDocument> sources;
    private final Map<String, Document> chunks;
    private final CatalogLifecycle lifecycle;
    private final long epoch;
    final reactor.core.publisher.Sinks.One<Void> invalidated = reactor.core.publisher.Sinks.one();

    public KnowledgeCatalog(List<KnowledgeDocument> documents) {
        this(documents, new CatalogLifecycle(), 0);
        lifecycle.initialize(this);
    }

    public static KnowledgeCatalog restore(JdbcCatalogStore store) {
        CatalogLifecycle lifecycle = new CatalogLifecycle(store);
        JdbcCatalogStore.State recovered = store.recovered();
        KnowledgeCatalog catalog = new KnowledgeCatalog(recovered.documents(), lifecycle, recovered.generation());
        lifecycle.initialize(catalog);
        return catalog;
    }

    KnowledgeCatalog(List<KnowledgeDocument> documents, CatalogLifecycle lifecycle, long epoch) {
        this.lifecycle = lifecycle;
        this.epoch = epoch;
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
                Map<String, Object> metadata = Map.ofEntries(Map.entry("source_id", source.sourceId()), Map.entry("source_type", source.sourceType()),
                        Map.entry("version", source.version()), Map.entry("visibility", source.visibility()), Map.entry("review_status", source.reviewStatus()),
                        Map.entry("updated_at", source.updatedAt().toString()), Map.entry("source_url", source.sourceUrl()),
                        Map.entry("content_hash", hash), Map.entry("chunk_id", id),
                        Map.entry("catalog_authority", lifecycle.authorityId()), Map.entry("catalog_epoch", Long.toString(epoch)));
                chunkMap.put(id, new Document(id, text.substring(actualStart, end), metadata));
                if (end == text.length()) break;
            }
        }
        sources = Collections.unmodifiableMap(sourceMap);
        chunks = Collections.unmodifiableMap(chunkMap);
    }

    public CatalogLifecycle lifecycle() { return lifecycle; }
    public long epoch() { return epoch; }
    public void requireCurrent() { lifecycle.requireCurrent(this); }
    List<KnowledgeDocument> documents() { return List.copyOf(sources.values()); }
    private static Document copy(Document d) { return new Document(d.getId(), d.getText(), new LinkedHashMap<>(d.getMetadata())); }
    public List<Document> chunks() { requireCurrent(); return chunks.values().stream().map(KnowledgeCatalog::copy).toList(); }
    public Set<String> sourceIds() { requireCurrent(); return sources.keySet(); }
    public KnowledgeDocument source(String id) { requireCurrent(); return sources.get(id); }

    public Document verified(Document candidate) {
        return verified(candidate, lifecycle.authorityId(), epoch);
    }

    Document verified(Document candidate, String indexAuthority, long indexEpoch) {
        requireCurrent();
        if (candidate == null) return null;
        Document current = chunks.get(candidate.getId());
        if (current == null) return null;
        for (Map.Entry<String, Object> field : current.getMetadata().entrySet()) {
            Object expected = switch (field.getKey()) {
                case "catalog_authority" -> indexAuthority;
                case "catalog_epoch" -> Long.toString(indexEpoch);
                default -> field.getValue();
            };
            if (!expected.equals(candidate.getMetadata().get(field.getKey()))) return null;
        }
        // 不采信向量库的正文，即使 payload 被篡改也只能取本地已审校版本。
        return copy(current);
    }

    public int keywordScore(Document chunk, String query) {
        requireCurrent();
        KnowledgeDocument source = sources.get(chunk.getMetadata().get("source_id"));
        if (source == null || source.keywords() == null) return 0;
        return (int) source.keywords().stream().filter(query::contains).count();
    }

    public static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n').strip(); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256不可用", e); }
    }
}
