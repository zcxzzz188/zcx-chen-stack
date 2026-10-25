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
    private record ReadAuthority(KnowledgeCatalog catalog, String indexAuthority, long indexEpoch) { }
    private volatile ReadAuthority publicationVerified;

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
        if (!publicationReady()) throw new RagFailure(RagFailure.Code.RECOVERY_REQUIRED);
    }

    void blockPublication() { publicationVerified = null; }
    boolean publicationReady() {
        ReadAuthority verified = publicationVerified;
        return !lifecycle.durable() || verified != null && verified.catalog() == lifecycle.snapshot();
    }
    boolean publicationMatches(String indexAuthority, Long indexEpoch) {
        ReadAuthority verified = publicationVerified;
        return verified != null && publicationReady() && Objects.equals(verified.indexAuthority(), indexAuthority)
                && Objects.equals(verified.indexEpoch(), indexEpoch);
    }
    void confirmPublication(KnowledgeCatalog catalog) { confirmPublication(catalog, lifecycle.authorityId(), catalog.epoch()); }
    void confirmPublication(KnowledgeCatalog catalog, String indexAuthority, long indexEpoch) {
        catalog.requireCurrent();
        publicationVerified = new ReadAuthority(catalog, indexAuthority, indexEpoch);
    }
    private Document verified(KnowledgeCatalog catalog, Document candidate) {
        ReadAuthority authority = publicationVerified;
        if (!lifecycle.durable()) return catalog.verified(candidate);
        if (authority == null || authority.catalog() != catalog) throw new RagFailure(RagFailure.Code.RECOVERY_REQUIRED);
        return catalog.verified(candidate, authority.indexAuthority(), authority.indexEpoch());
    }
    void publicationBudget(Runnable reservation) {
        requireReady();
        embedding.publicationReservation(reservation);
    }
    String embeddingSignature() { requireReady(); return index.embeddingSignature(); }
    void preparePublicationTarget(boolean create, String releaseOwner) {
        requireReady();
        index.verify(create, releaseOwner);
    }
    String verifyPublicationIndex() {
        return verifyPublicationIndex(lifecycle.authorityId(), lifecycle.snapshot().epoch());
    }
    String verifyPublicationIndex(String indexAuthority, long indexEpoch) {
        requireReady();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        String fingerprint = index.fingerprint(catalog.chunks(), indexAuthority, indexEpoch);
        catalog.requireCurrent();
        return fingerprint;
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

    /** 执行器已验证旧索引完整摘要；只编码新块，其余向量仅更新目录绑定。 */
    synchronized void syncReviewedIncremental(Set<String> previousChunkIds) {
        requireReady();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        List<Document> current = catalog.chunks();
        List<Document> changed = current.stream().filter(d -> !previousChunkIds.contains(d.getId())).toList();
        List<Document> retained = current.stream().filter(d -> previousChunkIds.contains(d.getId())).toList();
        index.upsert(changed);
        index.rebindCatalog(retained);
        index.deleteObsoleteChunks(changed);
        catalog.requireCurrent();
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
                Document chunk = verified(catalog, candidate);
                if (chunk != null) {
                    verified.putIfAbsent(chunk.getId(), chunk);
                    scores.merge(chunk.getId(), candidate.getScore(), Math::max);
                }
            }
            Map<String, Double> lexical = lexicalScores(catalog, query);
            // 仅在语义索引返回有效来源时补充定位；新增正文仍逐篇核验物理索引，空索引不得借目录绕过。
            if (!verified.isEmpty()) {
                Set<String> present = verified.values().stream().map(d -> (String) d.getMetadata().get("source_id")).collect(Collectors.toSet());
                List<String> supplements = lexical.keySet().stream()
                        .sorted(Comparator.comparingDouble((String id) -> lexical.get(id)).reversed())
                        .limit(properties.getSourceTopK()).filter(id -> !present.contains(id)).toList();
                for (String id : supplements) {
                    KnowledgeEvidence found = retrieveByTitle(catalog.source(id).title());
                    if (found.catalog() != catalog) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                    for (Document chunk : found.chunks()) {
                        verified.putIfAbsent(chunk.getId(), chunk);
                        scores.putIfAbsent(chunk.getId(), 0.0);
                    }
                }
            }
            // 用完整问题与审校标题、主题词交叉排序，避免正文中的通用操作词吸走业务问题。
            List<String> ordered = verified.values().stream()
                    .sorted(Comparator.comparingDouble((Document d) -> lexical.getOrDefault((String) d.getMetadata().get("source_id"), 0.0))
                            .thenComparingDouble(d -> scores.get(d.getId())).reversed())
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

    private Map<String, Double> lexicalScores(KnowledgeCatalog catalog, String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        String compact = normalized.replaceAll("(?:一|两|几|这|那)(?:篇|个|条)|(?:我|你|他|她)?自己的|(?:我|你|他|她)的", "");
        Set<String> terms = queryTerms(normalized);
        Map<String, String> texts = new LinkedHashMap<>();
        for (String id : catalog.sourceIds()) {
            KnowledgeDocument source = catalog.source(id);
            if ("PUBLIC".equals(source.visibility()) && "DOCUMENT_REVIEWED".equals(source.reviewStatus()) && CustomerServiceScope.permitsSource(id)) {
                texts.put(id, (source.title() + " " + String.join(" ", source.keywords() == null ? List.of() : source.keywords())).toLowerCase(Locale.ROOT));
            }
        }
        Map<String, Double> result = new HashMap<>();
        for (var entry : texts.entrySet()) {
            KnowledgeDocument source = catalog.source(entry.getKey());
            String title = source.title().toLowerCase(Locale.ROOT);
            List<String> titleMatches = terms.stream().filter(title::contains).toList();
            // 主题词按完整短语匹配，“审核未通过”不能碎配成“审核通过”。
            List<String> keywordMatches = (source.keywords() == null ? List.<String>of() : source.keywords()).stream()
                    .map(word -> word.toLowerCase(Locale.ROOT))
                    .filter(word -> word.codePointCount(0, word.length()) >= 3)
                    .filter(word -> normalized.contains(word) || containsKeyword(compact, word)).toList();
            if (titleMatches.size() < 2 && keywordMatches.isEmpty()) continue;
            Set<String> matched = new LinkedHashSet<>(titleMatches);
            matched.addAll(keywordMatches);
            double score = 0;
            for (String term : matched) {
                long frequency = texts.values().stream().filter(text -> text.contains(term)).count();
                score += Math.log(1.0 + (double) texts.size() / frequency) * (keywordMatches.contains(term) ? 2 : 1);
            }
            result.put(entry.getKey(), score);
        }
        return result;
    }

    boolean hasTopicAnchor(KnowledgeEvidence evidence, String query) {
        Map<String, Double> topics = lexicalScores(evidence.catalog(), query);
        return evidence.chunks().stream().anyMatch(chunk -> topics.containsKey(chunk.getMetadata().get("source_id")));
    }

    private Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        var words = java.util.regex.Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}]+").matcher(query);
        while (words.find()) {
            String word = words.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] points = word.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) terms.add(new String(points, i, 2));
            } else if (word.length() > 1) terms.add(word);
        }
        return terms;
    }

    private boolean containsKeyword(String query, String keyword) {
        if (query.contains(keyword)) return true;
        int[] points = keyword.codePoints().toArray();
        if (points.length < 4) return false;
        // 容许短语内部省略一字，但否定字不能省略，避免把反义问题匹配到同一规则。
        for (int i = 1; i + 1 < points.length; i++) {
            if ("不未无没非否勿莫别".indexOf(points[i]) >= 0) continue;
            String shorter = new String(points, 0, i) + new String(points, i + 1, points.length - i - 1);
            if (query.contains(shorter)) return true;
        }
        return false;
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
                Document chunk = verified(catalog, candidate);
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
