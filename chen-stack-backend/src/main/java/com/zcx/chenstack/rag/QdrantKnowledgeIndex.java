package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.RagProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorsFactory.vectors;

/** 官方 VectorStore + 显式集合校验；从不重建或删除集合。 */
public class QdrantKnowledgeIndex implements AutoCloseable {
    private static final UUID BINDING_ID = UUID.nameUUIDFromBytes("chen-rag-index-binding-v1".getBytes(StandardCharsets.UTF_8));
    private final QdrantClient client;
    private final QdrantVectorStore store;
    private final RagProperties properties;
    private final String signature;

    public QdrantKnowledgeIndex(RagProperties properties, GuardedEmbeddingModel embedding) {
        properties.validate();
        this.properties = properties;
        this.signature = KnowledgeCatalog.hash(properties.getEmbeddingBaseUrl() + "|" + properties.getEmbeddingModel() + "|1024|chunk-v1");
        var builder = QdrantGrpcClient.newBuilder(properties.getQdrantHost(), properties.getQdrantGrpcPort(), properties.isQdrantTls(), false)
                .withTimeout(Duration.ofSeconds(properties.getQdrantTimeoutSeconds()));
        if (!properties.getQdrantApiKey().isBlank()) builder.withApiKey(properties.getQdrantApiKey());
        client = new QdrantClient(builder.build());
        store = QdrantVectorStore.builder(client, embedding).collectionName(properties.getCollection()).initializeSchema(false).build();
    }

    public void verify(boolean createIfAbsent) {
        verify(createIfAbsent, null);
    }

    /** An approved release may only adopt a collection carrying its environment binding. */
    void verify(boolean createIfAbsent, String releaseOwner) {
        try {
            if (!client.collectionExistsAsync(properties.getCollection()).get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS)) {
                if (!createIfAbsent) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                client.createCollectionAsync(properties.getCollection(), Collections.VectorParams.newBuilder()
                        .setSize(1024).setDistance(Collections.Distance.Cosine).build())
                        .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS);
                float[] control = new float[1024]; control[0] = 1;
                var binding = Points.PointStruct.newBuilder().setId(id(BINDING_ID))
                        .setVectors(vectors(control)).putPayload("embedding_signature", JsonWithInt.Value.newBuilder().setStringValue(signature).build());
                if (releaseOwner != null) binding.putPayload("release_owner", JsonWithInt.Value.newBuilder().setStringValue(releaseOwner).build());
                client.upsertAsync(properties.getCollection(), List.of(binding.build()))
                        .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS);
            }
            var vector = client.getCollectionInfoAsync(properties.getCollection()).get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS)
                    .getConfig().getParams().getVectorsConfig();
            if (!vector.hasParams() || vector.getParams().getSize() != 1024 || vector.getParams().getDistance() != Collections.Distance.Cosine) {
                throw new RagFailure(RagFailure.Code.DIMENSION_MISMATCH);
            }
            var marker = client.retrieveAsync(properties.getCollection(), List.of(id(BINDING_ID)), true, false, null)
                    .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS);
            if (marker.size() != 1 || !signature.equals(marker.getFirst().getPayloadMap()
                    .getOrDefault("embedding_signature", JsonWithInt.Value.getDefaultInstance()).getStringValue())) {
                throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
            }
            if (releaseOwner != null && !releaseOwner.equals(marker.getFirst().getPayloadMap()
                    .getOrDefault("release_owner", JsonWithInt.Value.getDefaultInstance()).getStringValue())) {
                throw new IllegalStateException("RELEASE_TARGET_NOT_OWNED");
            }
        } catch (RagFailure failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
        catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    boolean collectionExists() {
        try { return client.collectionExistsAsync(properties.getCollection()).get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS); }
        catch (Exception error) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    String embeddingSignature() { return signature; }

    /** 完成检查点绑定实际向量与完整 payload；恢复只读，不重新计算 Embedding。 */
    String fingerprint(List<Document> expected, String authority, long epoch) {
        verify(false);
        try {
            if (client.countAsync(properties.getCollection(), null, true)
                    .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS) != expected.size() + 1L) {
                throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var output = new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            List<Document> ordered = expected.stream().sorted(Comparator.comparing(Document::getId)).toList();
            for (int start = 0; start < ordered.size(); start += 64) {
                List<Document> batch = ordered.subList(start, Math.min(start + 64, ordered.size()));
                List<Points.RetrievedPoint> points = client.retrieveAsync(properties.getCollection(),
                        batch.stream().map(d -> id(UUID.fromString(d.getId()))).toList(), true, true, null)
                        .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS);
                Map<String, Points.RetrievedPoint> byId = new HashMap<>();
                for (var point : points) {
                    if (byId.put(point.getId().getUuid(), point) != null) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                }
                if (byId.size() != batch.size()) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                for (Document document : batch) {
                    var point = byId.get(document.getId());
                    if (point == null || !point.getVectors().hasVector()) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                    Map<String, String> payload = new TreeMap<>();
                    point.getPayloadMap().forEach((key, value) -> {
                        if (value.getKindCase() != JsonWithInt.Value.KindCase.STRING_VALUE) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                        payload.put(key, value.getStringValue());
                    });
                    Map<String, String> canonical = new TreeMap<>();
                    document.getMetadata().forEach((key, value) -> canonical.put(key, value.toString()));
                    canonical.put("catalog_authority", authority); canonical.put("catalog_epoch", Long.toString(epoch));
                    canonical.put("embedding_signature", signature); canonical.put("doc_content", document.getText());
                    if (!canonical.equals(payload)) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                    output.writeUTF(document.getId());
                    for (var field : payload.entrySet()) { output.writeUTF(field.getKey()); output.writeUTF(field.getValue()); }
                    List<Float> vector = point.getVectors().getVector().getDataList();
                    if (vector.size() != 1024 || vector.stream().anyMatch(v -> !Float.isFinite(v))
                            || vector.stream().noneMatch(v -> v != 0)) throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
                    for (float value : vector) output.writeInt(Float.floatToIntBits(value));
                }
            }
            output.flush();
            return HexFormat.of().formatHex(digest.digest());
        } catch (RagFailure failure) { throw failure; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
        catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    public void upsert(List<Document> chunks) {
        verify(false);
        try {
            for (int i = 0; i < chunks.size(); i += 10) {
                List<Document> batch = chunks.subList(i, Math.min(chunks.size(), i + 10)).stream().map(doc -> {
                    Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                    metadata.put("embedding_signature", signature);
                    return new Document(doc.getId(), doc.getText(), metadata);
                }).toList();
                store.add(batch);
            }
        } catch (RagFailure failure) { throw failure; }
        catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    /** 物理索引操作，不代表权威目录撤权；业务调用须经RagKnowledgeService校验。 */
    public void deleteSource(String sourceId) {
        verify(false);
        try { store.delete(new FilterExpressionBuilder().eq("source_id", sourceId).build()); }
        catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    public void deleteObsoleteChunks(List<Document> current) {
        verify(false);
        Map<String, List<Object>> idsBySource = new LinkedHashMap<>();
        for (Document chunk : current) {
            idsBySource.computeIfAbsent((String) chunk.getMetadata().get("source_id"), key -> new ArrayList<>()).add(chunk.getId());
        }
        try {
            for (var source : idsBySource.entrySet()) {
                FilterExpressionBuilder filter = new FilterExpressionBuilder();
                store.delete(filter.and(filter.eq("source_id", source.getKey()), filter.nin("chunk_id", source.getValue())).build());
            }
        } catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    public List<Document> search(String query) {
        return search(query, properties.getChunkCandidateLimit(), properties.getSimilarityThreshold());
    }

    /** 仅按权威目录给出的chunk UUID读取；来源授权与完整性由服务层再次核验。 */
    public List<Document> readChunks(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty() || chunkIds.size() > 64) {
            throw new RagFailure(RagFailure.Code.INDEX_MISMATCH);
        }
        List<Points.PointId> ids = chunkIds.stream().map(UUID::fromString).map(uuid -> id(uuid)).toList();
        verify(false);
        try {
            return client.retrieveAsync(properties.getCollection(), ids, true, false, null)
                    .get(properties.getQdrantTimeoutSeconds(), TimeUnit.SECONDS).stream().map(point -> {
                        Map<String, Object> metadata = new HashMap<>();
                        point.getPayloadMap().forEach((key, value) -> {
                            if (value.getKindCase() == JsonWithInt.Value.KindCase.STRING_VALUE) metadata.put(key, value.getStringValue());
                        });
                        String text = (String) metadata.remove("doc_content");
                        return new Document(point.getId().getUuid(), text == null ? "" : text, metadata);
                    }).toList();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE);
        } catch (Exception failure) {
            throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE);
        }
    }

    private List<Document> search(String query, int topK, double threshold) {
        verify(false);
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        Filter.Expression filter = filters.and(filters.eq("visibility", "PUBLIC"),
                filters.and(filters.eq("review_status", "DOCUMENT_REVIEWED"), filters.eq("embedding_signature", signature))).build();
        try {
            return store.similaritySearch(SearchRequest.builder().query(query).topK(topK)
                    .similarityThreshold(threshold).filterExpression(filter).build());
        } catch (RagFailure failure) { throw failure; }
        catch (Exception failure) { throw new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE); }
    }

    @Override public void close() { client.close(); }
}
