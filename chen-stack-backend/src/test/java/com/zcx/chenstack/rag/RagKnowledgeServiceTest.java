package com.zcx.chenstack.rag;

import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real catalog, lifecycle and retrieval logic; only the physical vector index is replaced. */
class RagKnowledgeServiceTest {
    private KnowledgeDocument source(String id, String visibility, String review) {
        return new KnowledgeDocument(id, "操作帮助" + id, "编辑器中保存草稿。", "current-1", "PLATFORM_HELP",
                "/editor", visibility, review, Instant.EPOCH, List.of("草稿"));
    }

    private RagKnowledgeService service(KnowledgeCatalog catalog, QdrantKnowledgeIndex index) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        return new RagKnowledgeService(properties, catalog, index, null);
    }

    private Document scored(Document chunk) {
        return Document.builder().id(chunk.getId()).text(chunk.getText())
                .metadata(new LinkedHashMap<>(chunk.getMetadata())).score(0.99).build();
    }

    @Test void privateOrUnreviewedSourcesCannotUseOldPublicIndexPayloads() {
        KnowledgeCatalog approved = new KnowledgeCatalog(List.of(source("restricted", "PUBLIC", "DOCUMENT_REVIEWED")));
        for (KnowledgeDocument restricted : List.of(source("restricted", "PRIVATE", "DOCUMENT_REVIEWED"),
                source("restricted", "PUBLIC", "CONTENT_APPROVED"))) {
            KnowledgeCatalog current = new KnowledgeCatalog(List.of(restricted, source("other", "PUBLIC", "DOCUMENT_REVIEWED")));
            QdrantKnowledgeIndex index = mock(QdrantKnowledgeIndex.class);
            when(index.search(anyString())).thenReturn(List.of(scored(approved.chunks().getFirst())));
            RagKnowledgeService service = service(current, index);
            assertTrue(service.retrieve("保存草稿").chunks().isEmpty());
            assertTrue(service.retrieveByTitle(restricted.title()).chunks().isEmpty());
            verify(index, never()).readChunks(anyList());
        }
    }

    @Test void staleOrForgedMetadataCannotReplaceCanonicalReviewedFacts() {
        KnowledgeCatalog catalog = new KnowledgeCatalog(List.of(source("current", "PUBLIC", "DOCUMENT_REVIEWED")));
        Document canonical = catalog.chunks().getFirst();
        QdrantKnowledgeIndex index = mock(QdrantKnowledgeIndex.class);
        RagKnowledgeService service = service(catalog, index);
        for (var mutation : Map.of("version", "obsolete-0", "content_hash", "forged", "review_status", "DRAFT",
                "visibility", "PRIVATE", "catalog_authority", "other-authority").entrySet()) {
            Map<String, Object> metadata = new LinkedHashMap<>(canonical.getMetadata());
            metadata.put(mutation.getKey(), mutation.getValue());
            when(index.search(anyString())).thenReturn(List.of(Document.builder().id(canonical.getId())
                    .text("FORGED_TEXT").metadata(metadata).score(0.99).build()));
            assertTrue(service.retrieve("保存草稿").chunks().isEmpty(), mutation.getKey());
        }
        when(index.search(anyString())).thenReturn(List.of(Document.builder().id(canonical.getId())
                .text("FORGED_TEXT").metadata(canonical.getMetadata()).score(0.99).build()));
        KnowledgeEvidence evidence = service.retrieve("保存草稿");
        assertEquals(canonical.getText(), evidence.chunks().getFirst().getText());
        assertFalse(evidence.context().contains("FORGED_TEXT"));
    }

    @Test void revocationInvalidatesExistingEvidenceAndPreventsNewRetrieval() {
        KnowledgeCatalog catalog = new KnowledgeCatalog(List.of(source("revoked", "PUBLIC", "DOCUMENT_REVIEWED")));
        QdrantKnowledgeIndex index = mock(QdrantKnowledgeIndex.class);
        when(index.search(anyString())).thenReturn(List.of(scored(catalog.chunks().getFirst())));
        RagKnowledgeService service = service(catalog, index);
        KnowledgeEvidence evidence = service.retrieve("保存草稿");
        assertFalse(evidence.chunks().isEmpty());
        catalog.lifecycle().revoke("revoked", catalog.epoch());
        assertThrows(CatalogLifecycle.Invalidated.class, evidence::context);
        assertTrue(service.retrieve("保存草稿").chunks().isEmpty());
        verify(index).search("保存草稿");
    }
}
