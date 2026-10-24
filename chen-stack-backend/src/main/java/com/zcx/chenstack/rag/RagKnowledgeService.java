package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.RagProperties;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/** 可独立测试的公开知识处理链路；没有后台自动索引或数据库扫描。 */
@lombok.extern.slf4j.Slf4j
public class RagKnowledgeService implements AutoCloseable {
    private final RagProperties properties;
    private final CatalogLifecycle lifecycle;
    private final QdrantKnowledgeIndex index;
    private final GuardedEmbeddingModel embedding;
    private volatile String lastOperation = "NOT_CHECKED";
    private volatile String lastSync = "NOT_RUN";
    private volatile String lastSyncAt = "";
    private volatile KnowledgeCatalog publicationVerified;

    public RagKnowledgeService(RagProperties properties, KnowledgeCatalog catalog,
                               QdrantKnowledgeIndex index, GuardedEmbeddingModel embedding) {
        this(properties, catalog.lifecycle(), index, embedding);
    }

    public RagKnowledgeService(RagProperties properties, CatalogLifecycle lifecycle,
                               QdrantKnowledgeIndex index, GuardedEmbeddingModel embedding) {
        this.properties = properties; this.lifecycle = lifecycle; this.index = index; this.embedding = embedding;
    }

    public void requireReady() {
        lifecycle.snapshot();
        if (!properties.isEnabled()) throw new RagFailure(RagFailure.Code.DISABLED);
        if (index == null) throw new RagFailure(RagFailure.Code.MISSING_KEY);
        properties.validate();
    }

    private void requireReadable() {
        requireReady();
        if (lifecycle.durable() && publicationVerified != lifecycle.snapshot()) throw new RagFailure(RagFailure.Code.RECOVERY_REQUIRED);
    }

    void blockPublication() { publicationVerified = null; }
    boolean publicationReady() { return !lifecycle.durable() || publicationVerified == lifecycle.snapshot(); }
    void confirmPublication(KnowledgeCatalog catalog) { catalog.requireCurrent(); publicationVerified = catalog; }
    void publicationBudget(Runnable reservation) {
        requireReady();
        embedding.publicationReservation(reservation);
    }
    String embeddingSignature() { requireReady(); return index.embeddingSignature(); }
    void preparePublicationTarget(boolean create, String releaseOwner) {
        requireReady();
        index.verify(create, releaseOwner);
    }
    void verifyPublicationIndex() {
        requireReady();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        List<Document> expected = catalog.chunks();
        for (int start = 0; start < expected.size(); start += 64) {
            List<String> ids = expected.subList(start, Math.min(start + 64, expected.size())).stream().map(Document::getId).toList();
            List<Document> found = index.readChunks(ids);
            if (found.size() != ids.size() || found.stream().anyMatch(chunk -> catalog.verified(chunk) == null)
                    || !new HashSet<>(ids).equals(found.stream().map(Document::getId).collect(Collectors.toSet()))) {
                throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
            }
        }
        catalog.requireCurrent();
    }

    /** 由受控发布执行器显式调用；默认启动不执行索引同步。 */
    public synchronized void syncReviewed(boolean createIfAbsent) {
        requireReady();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        lastSyncAt = java.time.Instant.now().toString();
        try {
            index.verify(createIfAbsent);
            // 同一ID、版本和Hash对应同一chunk UUID，重复导入为upsert。
            List<Document> current = catalog.chunks();
            index.upsert(current);
            // 所有新块写入成功后，局部清理同一来源旧块，避免占用TopK；失败不得报告同步成功。
            index.deleteObsoleteChunks(current);
            catalog.requireCurrent();
            lastSync = "SUCCESS";
            lastOperation = "READY";
        } catch (RagFailure failure) {
            lastSync = failure.code().name(); lastOperation = lastSync;
            log.warn("RAG索引同步失败，状态={}", lastSync);
            throw failure;
        } catch (RuntimeException failure) {
            lastSync = "CATALOG_OR_SYNC_INVALIDATED"; lastOperation = lastSync;
            throw failure;
        }
    }

    /** 仅清理已在权威生命周期撤销的索引副本；删除失败不恢复来源授权。 */
    public synchronized void deleteSource(String sourceId) {
        requireReady();
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("来源ID无效");
        lastSyncAt = java.time.Instant.now().toString();
        try {
            KnowledgeDocument source = lifecycle.snapshot().source(sourceId);
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
        requireReadable();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        if (query == null || query.isBlank() || query.length() > properties.getMaxEmbeddingInputChars()) {
            throw new RagFailure(RagFailure.Code.EMBEDDING_UNAVAILABLE);
        }
        if (catalog.chunks().isEmpty()) { lastOperation = "NO_REVIEWED_KNOWLEDGE"; return new KnowledgeEvidence(List.of(), catalog); }
        try {
            List<Document> candidates = index.search(query);
            Map<String, Document> verified = new LinkedHashMap<>();
            Map<String, Double> scores = new HashMap<>();
            for (Document candidate : candidates.stream().limit(properties.getChunkCandidateLimit()).toList()) {
                if (candidate == null || candidate.getScore() == null || !Double.isFinite(candidate.getScore())
                        || candidate.getScore() < properties.getSimilarityThreshold() || candidate.getScore() > 1) continue;
                Document chunk = catalog.verified(candidate);
                if (chunk != null) {
                    verified.putIfAbsent(chunk.getId(), chunk);
                    scores.merge(chunk.getId(), candidate.getScore(), Math::max);
                }
            }
            // 以真实语义相似度排序，词面命中数不能把低相关资料顶到操作说明之前。
            List<String> ordered = verified.values().stream()
                    .sorted(Comparator.comparingDouble((Document d) -> scores.get(d.getId())).reversed())
                    .map(doc -> (String) doc.getMetadata().get("source_id"))
                    .filter(Objects::nonNull).distinct().toList();
            // 任一chunk命中即选用该来源，但整篇释放：长文档不会因topK按chunk截断而只剩半条规则。
            List<Document> accepted = acceptWholeSources(catalog, ordered);
            log.info("xiaochen retrieval candidates={} verified={} sources={} accepted_chunks={} accepted_sources={}",
                    candidates.size(), verified.size(), ordered.size(), accepted.size(),
                    accepted.stream().map(d -> d.getMetadata().get("source_id")).distinct().toList());
            lastOperation = accepted.isEmpty() ? "NO_HIT" : "READY";
            catalog.requireCurrent();
            return new KnowledgeEvidence(List.copyOf(accepted), catalog);
        } catch (RagFailure failure) {
            lastOperation = Thread.currentThread().isInterrupted() ? "CANCELLED" : failure.code().name();
            throw failure;
        }
    }

    /** 目录只用于选择主题；正文仍须通过物理索引、版本与来源校验。 */
    public KnowledgeCatalog reviewedCatalog() {
        requireReadable();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        catalog.requireCurrent();
        return catalog;
    }

    /** 主题定位只接受唯一的已审校公开标题，正文仍须存在于当前物理索引。 */
    public KnowledgeEvidence retrieveByTitle(String title) {
        requireReadable();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        if (title == null || title.isBlank() || title.length() > 80) return new KnowledgeEvidence(List.of(), catalog);
        List<String> matches = catalog.sourceIds().stream().filter(id -> {
            KnowledgeDocument source = catalog.source(id);
            return "PUBLIC".equals(source.visibility()) && "DOCUMENT_REVIEWED".equals(source.reviewStatus())
                    && title.equals(source.title());
        }).toList();
        if (matches.size() != 1) return new KnowledgeEvidence(List.of(), catalog);
        String sourceId = matches.getFirst();
        Set<String> expected = catalog.chunks().stream()
                .filter(chunk -> sourceId.equals(chunk.getMetadata().get("source_id")))
                .map(Document::getId).collect(Collectors.toSet());
        try {
            List<Document> indexed = index.readChunks(List.copyOf(expected));
            Set<String> verified = new HashSet<>();
            for (Document candidate : indexed) {
                Document chunk = catalog.verified(candidate);
                if (chunk == null || !expected.contains(chunk.getId()) || !verified.add(chunk.getId())) {
                    throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                }
            }
            if (!verified.equals(expected)) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
            List<Document> accepted = acceptWholeSources(catalog, matches);
            catalog.requireCurrent();
            lastOperation = accepted.isEmpty() ? "NO_HIT" : "READY";
            log.info("xiaochen topic_read source={} indexed_chunks={} accepted_chunks={}", sourceId, indexed.size(), accepted.size());
            return new KnowledgeEvidence(accepted, catalog);
        } catch (RagFailure failure) {
            lastOperation = failure.code().name();
            throw failure;
        }
    }

    /**
     * 以来源为单位整篇接受检索结果：命中的任一chunk即视为该来源被检索到。
     * 分块只影响定位，不影响授权；一个来源的全部chunk来自同一已审校文档，不可截断。
     * 预算按整篇来源计算，超预算的来源整体排除，避免释放缺少条件的半边规则。
     */
    private List<Document> acceptWholeSources(KnowledgeCatalog catalog, List<String> orderedSourceIds) {
        Map<String, List<Document>> chunksBySource = catalog.chunks().stream()
                .collect(Collectors.groupingBy(doc -> (String) doc.getMetadata().get("source_id"),
                        LinkedHashMap::new, Collectors.toList()));
        List<Document> accepted = new ArrayList<>();
        int acceptedSources = 0;
        for (String sourceId : orderedSourceIds) {
            if (acceptedSources == properties.getSourceTopK()) break;
            List<Document> chunks = chunksBySource.get(sourceId);
            if (chunks == null || chunks.isEmpty()) continue;
            List<Document> next = new ArrayList<>(accepted);
            next.addAll(chunks);
            // UTF-8字节数作为保守Token上界，预算包含证据JSON的结构开销。
            if (new KnowledgeEvidence(next, catalog).context().getBytes(StandardCharsets.UTF_8).length
                    > properties.getContextTokenBudget()) continue;
            accepted = next;
            acceptedSources++;
        }
        return List.copyOf(accepted);
    }

    /** 状态不触发联网探测或计费；NOT_CHECKED不伪装为健康。 */
    public Map<String, Object> diagnostics() {
        return Map.of("enabled", properties.isEnabled(), "defaultMode", "auto", "supportedModes", List.of("chat", "rag"),
                "ragAvailability", !properties.isEnabled() ? "DISABLED" : index == null ? "MISSING_KEY" : !publicationReady() ? "RECOVERY_REQUIRED" : "CONFIGURED",
                "lastOperation", lastOperation, "lastSync", lastSync, "lastSyncAt", lastSyncAt, "reviewedChunks", lifecycle.snapshot().chunks().size(),
                "embedding", embedding == null ? Map.of("requests", 0L, "inputChars", 0L, "failures", 0L, "reportedTokens", 0L) : embedding.counters());
    }

    public int chatTimeoutSeconds() { return properties.getChatTimeoutSeconds(); }
    public int contextByteBudget() { return properties.getContextTokenBudget(); }
    @Override public void close() { if (index != null) index.close(); }
}
