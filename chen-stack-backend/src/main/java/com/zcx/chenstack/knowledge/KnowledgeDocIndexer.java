package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.config.RagProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 文档通道的向量索引。使用独立 Qdrant 集合，与平台帮助集合完全隔离。
 *
 * <p>与旧通道保持一致的安全边界：1024 维 Cosine、Embedding 签名绑定、只信本地已审校正文、
 * 集合从不隐式重建或删除。</p>
 */
public class KnowledgeDocIndexer {
    private static final UUID BINDING_ID = UUID.nameUUIDFromBytes(
            "chen-knowledge-doc-binding-v1".getBytes(StandardCharsets.UTF_8));
    private static final int DIMENSIONS = 1024;
    static final int BATCH = KnowledgeChunker.EMBEDDING_BATCH;

    private final KnowledgeDocProperties properties;
    private final QdrantClient client;
    private final QdrantVectorStore store;
    private final String signature;
    private final String model;
    private final int timeoutSeconds;

    public KnowledgeDocIndexer(KnowledgeDocProperties properties, RagProperties rag,
                               EmbeddingModel embedding, QdrantClient client) {
        this.properties = properties;
        this.client = client;
        this.model = rag.getEmbeddingModel();
        this.timeoutSeconds = rag.getQdrantTimeoutSeconds();
        this.signature = com.zcx.chenstack.rag.KnowledgeCatalog.hash(
                rag.getEmbeddingBaseUrl() + "|" + rag.getEmbeddingModel() + "|" + DIMENSIONS + "|"
                        + KnowledgeChunker.VERSION);
        this.store = QdrantVectorStore.builder(client, embedding)
                .collectionName(properties.getCollection())
                .initializeSchema(false)
                .build();
    }

    public String signature() {
        return signature;
    }

    public String model() {
        return model;
    }

    public String collection() {
        return properties.getCollection();
    }

    /** 校验集合维度、距离与绑定标记；create 为 true 时允许创建。 */
    public void verify(boolean create) {
        try {
            boolean exists = client.collectionExistsAsync(properties.getCollection())
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            if (!exists) {
                if (!create) {
                    throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量集合不存在");
                }
                client.createCollectionAsync(properties.getCollection(), Collections.VectorParams.newBuilder()
                                .setSize(DIMENSIONS).setDistance(Collections.Distance.Cosine).build())
                        .get(timeoutSeconds, TimeUnit.SECONDS);
            }
            var vector = client.getCollectionInfoAsync(properties.getCollection())
                    .get(timeoutSeconds, TimeUnit.SECONDS)
                    .getConfig().getParams().getVectorsConfig();
            if (!vector.hasParams() || vector.getParams().getSize() != DIMENSIONS
                    || vector.getParams().getDistance() != Collections.Distance.Cosine) {
                throw KnowledgeDocException.unavailable("DIMENSION_MISMATCH", "文档向量集合维度或距离不一致");
            }
            var marker = client.retrieveAsync(properties.getCollection(), List.of(id(BINDING_ID)), true, false, null)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            // 首次装填中断后，只允许补齐仍然为空的集合。
            if (create && marker.isEmpty() && client.countAsync(properties.getCollection(), null, true)
                    .get(timeoutSeconds, TimeUnit.SECONDS) == 0L) {
                float[] control = new float[DIMENSIONS];
                control[0] = 1;
                var binding = Points.PointStruct.newBuilder().setId(id(BINDING_ID))
                        .setVectors(vectors(control))
                        .putPayload("embedding_signature",
                                JsonWithInt.Value.newBuilder().setStringValue(signature).build());
                client.upsertAsync(properties.getCollection(), List.of(binding.build()))
                        .get(timeoutSeconds, TimeUnit.SECONDS);
                marker = client.retrieveAsync(properties.getCollection(), List.of(id(BINDING_ID)), true, false, null)
                        .get(timeoutSeconds, TimeUnit.SECONDS);
            }
            if (marker.size() != 1 || !signature.equals(marker.getFirst().getPayloadMap()
                    .getOrDefault("embedding_signature", JsonWithInt.Value.getDefaultInstance()).getStringValue())) {
                throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量集合的 Embedding 签名不匹配");
            }
        } catch (KnowledgeDocException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        }
    }

    /** 把一批分块写入向量库；每批前调用 reserve 扣减预算。 */
    public void upsert(KnowledgeDocCatalog.Doc doc, List<KnowledgeDocCatalog.Chunk> chunks, Runnable reserve) {
        verify(false);
        upsertVerified(doc, chunks, reserve);
    }

    void upsertVerified(KnowledgeDocCatalog.Doc doc, List<KnowledgeDocCatalog.Chunk> chunks, Runnable reserve) {
        for (int start = 0; start < chunks.size(); start += BATCH) {
            List<KnowledgeDocCatalog.Chunk> batch = chunks.subList(start, Math.min(start + BATCH, chunks.size()));
            reserve.run();
            List<Document> documents = new ArrayList<>(batch.size());
            for (KnowledgeDocCatalog.Chunk chunk : batch) {
                documents.add(new Document(chunk.pointId(), chunk.text(), payload(doc, chunk)));
            }
            try {
                store.add(documents);
            } catch (KnowledgeDocException error) {
                throw error;
            } catch (Exception error) {
                throw KnowledgeDocException.unavailable("QDRANT_WRITE_FAILED", "文档分块写入向量库失败");
            }
        }
    }

    private Map<String, Object> payload(KnowledgeDocCatalog.Doc doc, KnowledgeDocCatalog.Chunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_key", doc.sourceKey());
        payload.put("document_id", Long.toString(doc.documentId()));
        payload.put("version_id", Long.toString(doc.versionId()));
        payload.put("version_label", doc.versionLabel());
        payload.put("version_sha256", doc.versionSha256());
        payload.put("title", doc.title());
        payload.put("source_ref_kind", doc.sourceRefKind());
        payload.put("source_url", doc.sourceUrl());
        payload.put("review_status", "APPROVED");
        payload.put("publish_status", "PUBLISHED");
        payload.put("visibility", "PUBLIC");
        payload.put("ordinal", Integer.toString(chunk.ordinal()));
        payload.put("chunk_sha256", chunk.sha256());
        payload.put("doc_content", chunk.text());
        payload.put("embedding_signature", signature);
        return payload;
    }

    /** 删除某来源当前版本之外的旧点。 */
    public void deleteStaleVersions(String sourceKey, long currentVersionId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq("source_key", sourceKey),
                builder.ne("version_id", Long.toString(currentVersionId))).build();
        try {
            store.delete(filter);
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_DELETE_FAILED", "旧版本向量清理失败");
        }
    }

    /** 删除某来源全部点（撤销/下架）。 */
    public void deleteSource(String sourceKey) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        try {
            store.delete(builder.eq("source_key", sourceKey).build());
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_DELETE_FAILED", "来源向量清理失败");
        }
    }

    public List<Document> search(String query, int topK, double threshold) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression filter = builder.and(
                builder.eq("embedding_signature", signature),
                builder.and(builder.eq("review_status", "APPROVED"),
                        builder.and(builder.eq("publish_status", "PUBLISHED"),
                                builder.eq("visibility", "PUBLIC")))).build();
        try {
            return store.similaritySearch(SearchRequest.builder().query(query).topK(topK)
                    .similarityThreshold(threshold).filterExpression(filter).build());
        } catch (KnowledgeDocException error) {
            throw error;
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量检索失败");
        }
    }

    /** 仅按权威快照给出的 point id 读取，正文与状态由上层再次核验。 */
    public List<Document> readChunks(List<String> pointIds) {
        verify(false);
        return readVerifiedChunks(pointIds);
    }

    List<Document> readVerifiedChunks(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty() || pointIds.size() > 128) {
            throw KnowledgeDocException.bad("INDEX_MISMATCH", "分块读取数量非法");
        }
        try {
            var ids = pointIds.stream().map(UUID::fromString).map(io.qdrant.client.PointIdFactory::id).toList();
            return client.retrieveAsync(properties.getCollection(), ids, true, false, null)
                    .get(timeoutSeconds, TimeUnit.SECONDS).stream().map(point -> {
                        Map<String, Object> metadata = new HashMap<>();
                        point.getPayloadMap().forEach((key, value) -> {
                            if (value.getKindCase() == JsonWithInt.Value.KindCase.STRING_VALUE) {
                                metadata.put(key, value.getStringValue());
                            }
                        });
                        String text = (String) metadata.remove("doc_content");
                        return new Document(point.getId().getUuid(), text == null ? "" : text, metadata);
                    }).toList();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        }
    }

    /** 索引指纹：点数量、payload 与向量全部纳入，用于证明索引未被漂移。 */
    public String fingerprint(List<KnowledgeDocCatalog.Chunk> expected) {
        verify(false);
        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var output = new java.io.DataOutputStream(
                    new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            List<KnowledgeDocCatalog.Chunk> ordered = expected.stream()
                    .sorted(Comparator.comparing(KnowledgeDocCatalog.Chunk::pointId)).toList();
            for (int start = 0; start < ordered.size(); start += 64) {
                List<KnowledgeDocCatalog.Chunk> batch = ordered.subList(start, Math.min(start + 64, ordered.size()));
                List<Points.RetrievedPoint> points = client.retrieveAsync(properties.getCollection(),
                                batch.stream().map(chunk -> id(UUID.fromString(chunk.pointId()))).toList(), true, true, null)
                        .get(timeoutSeconds, TimeUnit.SECONDS);
                Map<String, Points.RetrievedPoint> byId = new HashMap<>();
                for (var point : points) {
                    if (byId.put(point.getId().getUuid(), point) != null) {
                        throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量存在重复点");
                    }
                }
                if (byId.size() != batch.size()) {
                    throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量缺少分块");
                }
                for (KnowledgeDocCatalog.Chunk chunk : batch) {
                    var point = byId.get(chunk.pointId());
                    if (point == null || !point.getVectors().hasVector()) {
                        throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量缺失");
                    }
                    Map<String, String> payload = new java.util.TreeMap<>();
                    point.getPayloadMap().forEach((key, value) -> payload.put(key, value.getStringValue()));
                    if (!chunk.sha256().equals(payload.get("chunk_sha256"))
                            || !chunk.pointId().equals(point.getId().getUuid())) {
                        throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量载荷漂移");
                    }
                    output.writeUTF(chunk.pointId());
                    for (var field : payload.entrySet()) {
                        output.writeUTF(field.getKey());
                        output.writeUTF(field.getValue());
                    }
                    List<Float> vector = point.getVectors().getVector().getDataList();
                    if (vector.size() != DIMENSIONS || vector.stream().anyMatch(value -> !Float.isFinite(value))) {
                        throw KnowledgeDocException.unavailable("INDEX_MISMATCH", "文档向量维度异常");
                    }
                    for (float value : vector) {
                        output.writeInt(Float.floatToIntBits(value));
                    }
                }
            }
            output.flush();
            return HexFormat.of().formatHex(digest.digest());
        } catch (KnowledgeDocException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("QDRANT_UNAVAILABLE", "文档向量库不可用");
        }
    }


}
