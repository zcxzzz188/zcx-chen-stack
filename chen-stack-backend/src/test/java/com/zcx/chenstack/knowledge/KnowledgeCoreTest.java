package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.config.RagProperties;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeCoreTest {
    @TempDir
    Path storage;

    @Test
    void resumesInterruptedBindingOnlyForAnEmptyCollection() {
        QdrantClient qdrant = mock(QdrantClient.class);
        KnowledgeDocProperties properties = new KnowledgeDocProperties();
        String collection = properties.getCollection();
        var index = new KnowledgeDocIndexer(properties, new RagProperties(), mock(EmbeddingModel.class), qdrant);
        var info = Collections.CollectionInfo.newBuilder()
                .setConfig(Collections.CollectionConfig.newBuilder()
                        .setParams(Collections.CollectionParams.newBuilder()
                                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                                        .setParams(Collections.VectorParams.newBuilder()
                                                .setSize(1024).setDistance(Collections.Distance.Cosine)))))
                .build();
        var marker = Points.RetrievedPoint.newBuilder().putPayload("embedding_signature",
                JsonWithInt.Value.newBuilder().setStringValue(index.signature()).build()).build();
        var foreignMarker = marker.toBuilder().putPayload("embedding_signature",
                JsonWithInt.Value.newBuilder().setStringValue("another-embedding-signature").build()).build();
        when(qdrant.collectionExistsAsync(collection)).thenReturn(immediateFuture(true));
        when(qdrant.getCollectionInfoAsync(collection)).thenReturn(immediateFuture(info));
        when(qdrant.retrieveAsync(eq(collection), anyList(), eq(true), eq(false), isNull()))
                .thenReturn(immediateFuture(List.of()), immediateFuture(List.of(marker)),
                        immediateFuture(List.of()), immediateFuture(List.of(foreignMarker)), immediateFuture(List.of()));
        when(qdrant.countAsync(collection, null, true)).thenReturn(immediateFuture(0L), immediateFuture(1L));
        when(qdrant.upsertAsync(eq(collection), anyList())).thenReturn(immediateFuture(Points.UpdateResult.getDefaultInstance()));

        index.verify(true);
        assertEquals("INDEX_MISMATCH", assertThrows(KnowledgeDocException.class, () -> index.verify(true)).code());
        assertEquals("INDEX_MISMATCH", assertThrows(KnowledgeDocException.class, () -> index.verify(true)).code());
        assertEquals("INDEX_MISMATCH", assertThrows(KnowledgeDocException.class, () -> index.verify(false)).code());
        verify(qdrant, times(1)).upsertAsync(eq(collection), anyList());
    }

    @Test
    void emptyRagReturnsNoEvidenceWithoutAccessingQdrant() throws IOException {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(Boolean.FALSE);
        KnowledgeVectors vectors = mock(KnowledgeVectors.class);
        KnowledgeSettings settings = new KnowledgeSettings(storage.resolve("settings.yml"),
                "hybrid", "test-model", .3, 1024, 100);
        KnowledgeSearch search = new KnowledgeSearch(jdbc, vectors, settings, mock(OpenAiChatModel.class), false);

        assertTrue(search.retrieve("空库主题", "rag").hits().isEmpty());
        verifyNoInteractions(vectors);
    }

    @Test
    void originalBytesArePreservedAndOnlyGeneratedRelativeKeysCanBeRead() throws Exception {
        KnowledgeFiles files = new KnowledgeFiles(storage);
        byte[] bytes = "\uFEFF# 原文\r\n正文\r\n".getBytes(StandardCharsets.UTF_8);
        String key = files.save(bytes, "md");

        assertEquals(key, files.save(bytes, "md"));
        assertArrayEquals(bytes, files.read(key, bytes.length));
        assertThrows(KnowledgeDocException.class, () -> files.read("../" + key, bytes.length));
        assertThrows(KnowledgeDocException.class, () -> files.read(key, bytes.length - 1));
        files.delete(key);
        assertThrows(KnowledgeDocException.class, () -> files.read(key, bytes.length));
    }

    @Test
    void wikiValidationRejectsModelClaimsNotInTheOriginal() {
        var sources = List.of(new KnowledgeWikiGenerator.Source("专栏", "发布前请先填写标题。\n正文由作者编辑。"));
        var valid = new KnowledgeWikiGenerator.Extraction("专栏", "发布前请先填写标题。",
                List.of("发布前请先填写标题。", "正文由作者编辑。"));
        assertEquals("发布前请先填写标题。\n\n正文由作者编辑。", KnowledgeWikiGenerator.verify(valid, sources).content());
        var fabricated = new KnowledgeWikiGenerator.Extraction("专栏", "发布前请先填写标题。", List.of("自动导入文章。"));
        assertThrows(KnowledgeDocException.class, () -> KnowledgeWikiGenerator.verify(fabricated, sources));
    }
}
