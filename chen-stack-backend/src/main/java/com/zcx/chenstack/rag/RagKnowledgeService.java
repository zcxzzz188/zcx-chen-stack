package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.RagProperties;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 可独立测试的公开知识处理链路；没有后台自动索引或数据库扫描。 */
@lombok.extern.slf4j.Slf4j
public class RagKnowledgeService implements AutoCloseable {
    private final RagProperties properties;
    private final KnowledgeCatalog catalog;
    private final QdrantKnowledgeIndex index;
    private final GuardedEmbeddingModel embedding;
    private volatile String lastOperation = "NOT_CHECKED";
    private volatile String lastSync = "NOT_RUN";
    private volatile String lastSyncAt = "";

    public RagKnowledgeService(RagProperties properties, KnowledgeCatalog catalog,
                               QdrantKnowledgeIndex index, GuardedEmbeddingModel embedding) {
        this.properties = properties; this.catalog = catalog; this.index = index; this.embedding = embedding;
    }

    public void requireReady() {
        if (!properties.isEnabled()) throw new RagFailure(RagFailure.Code.DISABLED);
        if (index == null) throw new RagFailure(RagFailure.Code.MISSING_KEY);
        properties.validate();
    }

    /** 只能由明确的本地索引操作调用；默认应用启动没有调用入口。 */
    public synchronized void syncReviewed(boolean createIfAbsent) {
        requireReady();
        lastSyncAt = java.time.Instant.now().toString();
        try {
            index.verify(createIfAbsent);
            // 同一ID、版本和Hash对应同一chunk UUID，重复导入为upsert。
            List<Document> current = catalog.chunks();
            index.upsert(current);
            // 所有新块写入成功后，局部清理同一来源旧块，避免占用TopK；失败不得报告同步成功。
            index.deleteObsoleteChunks(current);
            lastSync = "SUCCESS";
            lastOperation = "READY";
        } catch (RagFailure failure) {
            lastSync = failure.code().name(); lastOperation = lastSync;
            log.warn("RAG索引同步失败，状态={}", lastSync);
            throw failure;
        }
    }

    /** 仅清理已在当前权威目录撤销的索引副本；资源目录更新须重新加载所有实例。 */
    public synchronized void deleteSource(String sourceId) {
        requireReady();
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("来源ID无效");
        lastSyncAt = java.time.Instant.now().toString();
        try {
            KnowledgeDocument source = catalog.source(sourceId);
            if (source != null && "PUBLIC".equals(source.visibility()) && "DOCUMENT_REVIEWED".equals(source.reviewStatus())) {
                throw new RagFailure(RagFailure.Code.SOURCE_STILL_APPROVED);
            }
            index.deleteSource(sourceId);
            lastSync = "INDEX_DELETE_SUCCESS"; lastOperation = lastSync;
        }
        catch (RagFailure failure) {
            lastSync = failure.code().name(); lastOperation = lastSync;
            log.warn("RAG索引删除失败，状态={}", lastSync); throw failure;
        }
    }

    public KnowledgeEvidence retrieve(String query) {
        requireReady();
        if (query == null || query.isBlank() || query.length() > properties.getMaxEmbeddingInputChars()) {
            throw new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE);
        }
        if (catalog.chunks().isEmpty()) { lastOperation = "NO_REVIEWED_KNOWLEDGE"; return new KnowledgeEvidence(List.of(), catalog); }
        try {
            List<Document> candidates = index.search(query);
            Map<String, Document> verified = new LinkedHashMap<>();
            for (Document candidate : candidates) {
                Document chunk = catalog.verified(candidate);
                if (chunk != null) verified.putIfAbsent(chunk.getId(), chunk);
            }
            // 轻量关键词只重排向量候选，不在故障、删除或无命中时绕过索引。
            List<Document> ordered = verified.values().stream()
                    .sorted(Comparator.comparingInt((Document d) -> catalog.keywordScore(d, query)).reversed()).toList();
            List<Document> accepted = new ArrayList<>();
            for (Document doc : ordered) {
                if (accepted.size() == properties.getTopK()) break;
                accepted.add(doc);
                // UTF-8字节数作为保守Token上界，预算包含证据JSON的结构开销。
                if (new KnowledgeEvidence(accepted, catalog).context().getBytes(StandardCharsets.UTF_8).length > properties.getContextTokenBudget()) {
                    accepted.removeLast();
                }
            }
            lastOperation = accepted.isEmpty() ? "NO_HIT" : "READY";
            return new KnowledgeEvidence(List.copyOf(accepted), catalog);
        } catch (RagFailure failure) {
            lastOperation = Thread.currentThread().isInterrupted() ? "CANCELLED" : failure.code().name();
            throw failure;
        }
    }

    /** 状态不触发联网探测或计费；NOT_CHECKED不伪装为健康。 */
    public Map<String, Object> diagnostics() {
        return Map.of("enabled", properties.isEnabled(), "defaultMode", "chat", "supportedModes", List.of("chat", "rag"),
                "ragAvailability", !properties.isEnabled() ? "DISABLED" : index == null ? "MISSING_KEY" : "CONFIGURED",
                "lastOperation", lastOperation, "lastSync", lastSync, "lastSyncAt", lastSyncAt, "reviewedChunks", catalog.chunks().size(),
                "embedding", embedding == null ? Map.of("requests", 0L, "inputChars", 0L, "failures", 0L, "reportedTokens", 0L) : embedding.counters());
    }

    public int chatTimeoutSeconds() { return properties.getChatTimeoutSeconds(); }
    @Override public void close() { if (index != null) index.close(); }
}
