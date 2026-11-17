package com.zcx.chenstack.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档检索的权威内存快照。
 *
 * <p>不可变；每次数据库目录代次（{@code knowledge_catalog_state.revision}）变化时整体重建。
 * 不使用 {@code COUNT(*)+MAX(id)} 判断失效，因为删除与回滚会让这两个值不可靠。</p>
 *
 * <p>检索到的向量命中必须逐个与本快照比对，不采信 Qdrant 里的正文与状态。</p>
 */
public final class KnowledgeDocCatalog {
    /** 单个来源允许的最大分块数，与 20000 字符 / 320 步长上界一致，留出余量。 */
    static final int MAX_CHUNKS_PER_SOURCE = 128;

    private final long revision;
    private final Map<String, Doc> documents;
    private final Map<String, Chunk> chunks;
    private final Map<Long, Doc> byVersionId;

    private KnowledgeDocCatalog(long revision, List<Doc> documents, List<Chunk> chunks) {
        this.revision = revision;
        Map<String, Doc> docs = new LinkedHashMap<>();
        Map<String, Chunk> chunkMap = new LinkedHashMap<>();
        Map<Long, Doc> versions = new LinkedHashMap<>();
        for (Doc doc : documents) {
            docs.put(doc.sourceKey(), doc);
            versions.put(doc.versionId(), doc);
        }
        for (Chunk chunk : chunks) {
            Doc doc = versions.get(chunk.versionId());
            if (doc == null) {
                continue;
            }
            chunkMap.put(chunk.pointId(), chunk.withSource(doc.sourceKey()));
        }
        this.documents = Collections.unmodifiableMap(docs);
        this.chunks = Collections.unmodifiableMap(chunkMap);
        this.byVersionId = Collections.unmodifiableMap(versions);
    }

    public static KnowledgeDocCatalog empty() {
        return new KnowledgeDocCatalog(-1, List.of(), List.of());
    }

    public static KnowledgeDocCatalog of(long revision, List<KnowledgeDocStore.PublishedVersionRow> versions,
                                         List<KnowledgeDocStore.ChunkRow> chunkRows) {
        List<Doc> docs = new ArrayList<>(versions.size());
        for (KnowledgeDocStore.PublishedVersionRow row : versions) {
            docs.add(new Doc(row.documentId(), row.versionId(), row.sourceKey(), row.title(), row.summary(),
                    row.versionLabel(), row.normalizedSha256(), row.sourceRefKind(),
                    row.sourceUrl() == null ? "" : row.sourceUrl(),
                    row.publishedAt() == null ? "" : row.publishedAt().toString()));
        }
        List<Chunk> chunks = new ArrayList<>(chunkRows.size());
        for (KnowledgeDocStore.ChunkRow row : chunkRows) {
            chunks.add(new Chunk(row.pointId(), row.versionId(), row.ordinal(), row.text(),
                    row.chunkSha256(), row.startOffset(), row.endOffset()));
        }
        return new KnowledgeDocCatalog(revision, docs, chunks);
    }

    public long revision() {
        return revision;
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public int documentCount() {
        return documents.size();
    }

    public int chunkCount() {
        return chunks.size();
    }

    public Set<String> sourceKeys() {
        return documents.keySet();
    }

    public Doc document(String sourceKey) {
        return documents.get(sourceKey);
    }

    public Doc documentByVersionId(long versionId) {
        return byVersionId.get(versionId);
    }

    /** 某来源当前版本的全部分块，按 ordinal 排序。 */
    public List<Chunk> chunksOf(String sourceKey) {
        Doc doc = documents.get(sourceKey);
        if (doc == null) {
            return List.of();
        }
        List<Chunk> result = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (chunk.versionId() == doc.versionId()) {
                result.add(chunk);
            }
        }
        result.sort(java.util.Comparator.comparingInt(Chunk::ordinal));
        return List.copyOf(result);
    }

    public Chunk chunk(String pointId) {
        return chunks.get(pointId);
    }

    /**
     * 校验向量库命中：point id 必须存在于当前快照，且关键 payload 字段与快照一致。
     * 不一致视为索引漂移，返回 null，由调用方决定失败关闭。
     */
    public Chunk verified(String pointId, Map<String, Object> payload) {
        Chunk chunk = chunks.get(pointId);
        if (chunk == null) {
            return null;
        }
        Doc doc = byVersionId.get(chunk.versionId());
        if (doc == null) {
            return null;
        }
        if (!matches(payload, "source_key", doc.sourceKey())
                || !matches(payload, "version_id", Long.toString(doc.versionId()))
                || !matches(payload, "version_sha256", doc.versionSha256())
                || !matches(payload, "chunk_sha256", chunk.sha256())
                || !matches(payload, "ordinal", Integer.toString(chunk.ordinal()))
                || !matches(payload, "review_status", "APPROVED")
                || !matches(payload, "publish_status", "PUBLISHED")
                || !matches(payload, "visibility", "PUBLIC")) {
            return null;
        }
        return chunk;
    }

    private static boolean matches(Map<String, Object> payload, String key, String expected) {
        Object value = payload == null ? null : payload.get(key);
        return value != null && Objects.equals(expected, value.toString());
    }

    /** 已发布文档。 */
    public record Doc(long documentId, long versionId, String sourceKey, String title, String summary,
                      String versionLabel, String versionSha256, String sourceRefKind, String sourceUrl,
                      String publishedAt) {
    }

    /** 已发布分块。 */
    public record Chunk(String pointId, long versionId, int ordinal, String text, String sha256,
                        int startOffset, int endOffset) {
        Chunk withSource(String ignored) {
            return this;
        }
    }

    /** 供工具与诊断使用的标题集合。 */
    public List<String> titles() {
        Set<String> titles = new LinkedHashSet<>();
        documents.values().forEach(doc -> titles.add(doc.title()));
        return List.copyOf(titles);
    }
}
