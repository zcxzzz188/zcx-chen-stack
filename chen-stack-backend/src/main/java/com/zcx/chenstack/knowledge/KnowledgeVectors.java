package com.zcx.chenstack.knowledge;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.JsonWithInt;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.qdrant.client.PointIdFactory.id;

@Component
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
@lombok.extern.slf4j.Slf4j
public class KnowledgeVectors {
    private final QdrantClient client;
    private final KnowledgeDocIndexer index;
    private final int timeoutSeconds;
    private final double similarityThreshold;
    private final String legacyCollection;

    public KnowledgeVectors(RagProperties rag, ReleaseProviderBudget budget,
                            @Value("${chen.knowledge.simple.collection:chen_knowledge_v1}") String collection) {
        if (!collection.matches("chen_knowledge_[a-z0-9_]{1,60}") || collection.equals(rag.getCollection())
                || collection.startsWith("chen_knowledge_docs_")) {
            throw new IllegalArgumentException("知识向量集合必须独立于历史索引");
        }
        timeoutSeconds = rag.getQdrantTimeoutSeconds();
        similarityThreshold = rag.getSimilarityThreshold();
        legacyCollection = rag.getCollection();
        KnowledgeDocProperties properties = new KnowledgeDocProperties();
        properties.setCollection(collection);
        var builder = QdrantGrpcClient.newBuilder(rag.getQdrantHost(), rag.getQdrantGrpcPort(), rag.isQdrantTls(), false)
                .withTimeout(Duration.ofSeconds(timeoutSeconds));
        if (!rag.getQdrantApiKey().isBlank()) {
            builder.withApiKey(rag.getQdrantApiKey());
        }
        client = new QdrantClient(builder.build()) {
            @Override
            public ListenableFuture<Points.UpdateResult> upsertAsync(String collectionName, List<Points.PointStruct> points) {
                long started = System.nanoTime();
                return measure(super.upsertAsync(collectionName, points), "qdrant_write", points.size(), started);
            }

            @Override
            public ListenableFuture<List<Points.ScoredPoint>> searchAsync(Points.SearchPoints search) {
                long started = System.nanoTime();
                var timing = com.zcx.chenstack.service.CustomerServiceTiming.current();
                var result = super.searchAsync(search);
                result.addListener(() -> timing.stage("QDRANT_SEARCH", started), MoreExecutors.directExecutor());
                return measure(result, "qdrant_search", search.getLimit(), started);
            }
        };
        index = new KnowledgeDocIndexer(properties, rag, RagConfig.embedding(rag, budget), client);
    }

    private static <T> ListenableFuture<T> measure(ListenableFuture<T> future, String stage, long size, long started) {
        future.addListener(() -> log.info("knowledge stage={} size={} millis={}", stage, size,
                Duration.ofNanos(System.nanoTime() - started).toMillis()), MoreExecutors.directExecutor());
        return future;
    }

    public void prepare(long knowledgeId, long revision, String title, String content,
                        List<KnowledgeChunker.Chunk> chunks, boolean allowCollectionCreation) {
        index.verify(allowCollectionCreation);
        String sourceKey = "doc-" + knowledgeId;
        String label = Long.toString(revision);
        var doc = new KnowledgeDocCatalog.Doc(knowledgeId, revision, sourceKey, title, "", label,
                KnowledgeChunker.sha256(content), "DOCUMENT", "", "");
        List<String> ids = chunks.stream().map(chunk -> chunk.pointId().toString()).toList();
        List<KnowledgeDocCatalog.Chunk> missing = missing(chunks, ids);
        if (!missing.isEmpty()) {
            index.upsertVerified(doc, missing, () -> {});
            if (!missing(chunks, ids).isEmpty()) {
                throw KnowledgeDocException.unavailable("KNOWLEDGE_INDEX_INCOMPLETE", "向量写入尚未完整确认");
            }
        }
    }

    private List<KnowledgeDocCatalog.Chunk> missing(List<KnowledgeChunker.Chunk> chunks, List<String> ids) {
        Map<String, Document> found = new HashMap<>();
        index.readVerifiedChunks(ids).forEach(hit -> found.put(hit.getId(), hit));
        return chunks.stream().filter(chunk -> {
            Document hit = found.get(chunk.pointId().toString());
            return hit == null || !hit.getText().equals(chunk.text())
                    || !index.signature().equals(hit.getMetadata().get("embedding_signature"));
        }).map(chunk -> new KnowledgeDocCatalog.Chunk(chunk.pointId().toString(), 0, chunk.ordinal(),
                chunk.text(), chunk.chunkSha256(), chunk.startOffset(), chunk.endOffset())).toList();
    }

    public List<Document> search(String query, int limit) {
        index.verify(false);
        return index.search(query, limit, similarityThreshold);
    }

    void copyLegacy(String sourceKey, String version, String content, List<KnowledgeChunker.Chunk> chunks) {
        index.verify(true);
        String hash = KnowledgeChunker.sha256(content);
        var pointIds = chunks.stream().map(chunk -> id(chunk.pointId())).toList();
        try {
            var points = client.retrieveAsync(legacyCollection, pointIds, true, true, null)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            Map<String, Points.RetrievedPoint> byId = new HashMap<>();
            points.forEach(point -> byId.put(point.getId().getUuid(), point));
            List<Points.PointStruct> verified = chunks.stream().map(chunk -> {
                var point = byId.get(chunk.pointId().toString());
                if (point == null || !sourceKey.equals(payload(point, "source_id"))
                        || !version.equals(payload(point, "version")) || !hash.equals(payload(point, "content_hash"))
                        || !chunk.text().equals(payload(point, "doc_content"))
                        || !index.signature().equals(payload(point, "embedding_signature"))
                        || !"PUBLIC".equals(payload(point, "visibility"))
                        || !"DOCUMENT_REVIEWED".equals(payload(point, "review_status"))) {
                    throw unavailable();
                }
                List<Float> values = point.getVectors().getVector().getDataList();
                if (values.size() != 1024 || values.stream().anyMatch(value -> !Float.isFinite(value))
                        || values.stream().noneMatch(value -> value != 0)) {
                    throw unavailable();
                }
                return Points.PointStruct.newBuilder().setId(point.getId()).setVectors(io.qdrant.client.VectorsFactory.vectors(values))
                        .putPayload("doc_content", field(chunk.text()))
                        .putPayload("embedding_signature", field(index.signature()))
                        .putPayload("visibility", field("PUBLIC")).putPayload("review_status", field("APPROVED"))
                        .putPayload("publish_status", field("PUBLISHED")).build();
            }).toList();
            client.upsertAsync(Points.UpsertPoints.newBuilder().setCollectionName(index.collection())
                            .addAllPoints(verified).setWait(true).build())
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            if (!missing(chunks, chunks.stream().map(chunk -> chunk.pointId().toString()).toList()).isEmpty()) {
                throw unavailable();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    private static String payload(Points.RetrievedPoint point, String name) {
        return point.getPayloadMap().getOrDefault(name, JsonWithInt.Value.getDefaultInstance()).getStringValue();
    }

    private static JsonWithInt.Value field(String value) {
        return JsonWithInt.Value.newBuilder().setStringValue(value).build();
    }

    public void delete(List<String> pointIds) {
        if (pointIds.isEmpty()) {
            return;
        }
        index.verify(false);
        var selection = Points.PointsSelector.newBuilder().setPoints(Points.PointsIdsList.newBuilder()
                .addAllIds(pointIds.stream().map(UUID::fromString).map(uuid -> id(uuid)).toList()));
        try {
            client.deleteAsync(Points.DeletePoints.newBuilder().setCollectionName(index.collection())
                            .setPoints(selection).setWait(true).build())
                    .get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failure) {
            throw unavailable();
        }
    }

    @PreDestroy
    void close() {
        client.close();
    }

    private static KnowledgeDocException unavailable() {
        return KnowledgeDocException.unavailable("KNOWLEDGE_INDEX_UNAVAILABLE", "知识向量库暂不可用");
    }
}
