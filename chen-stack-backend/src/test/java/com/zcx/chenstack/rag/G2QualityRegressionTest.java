package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic index replay of frozen questions; this does not measure real embeddings or generated answers. */
class G2QualityRegressionTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private KnowledgeCatalog catalog;
    private QdrantKnowledgeIndex index;
    private RagKnowledgeService service;

    @BeforeEach void setup() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("knowledge/platform-help.json")) {
            catalog = new KnowledgeCatalog(Arrays.stream(json.readValue(input, KnowledgeDocument[].class))
                    .filter(source -> PublicationManifest.userSourceIds().contains(source.sourceId()))
                    .map(source -> new KnowledgeDocument(source.sourceId(), source.title(), source.text(), source.version(),
                            source.sourceType(), source.sourceUrl(), "PUBLIC", "DOCUMENT_REVIEWED", source.updatedAt(), source.keywords())).toList());
        }
        index = mock(QdrantKnowledgeIndex.class);
        when(index.readChunks(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return catalog.chunks().stream().filter(chunk -> ids.contains(chunk.getId())).toList();
        });
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        service = new RagKnowledgeService(properties, catalog, index, null);
    }

    private Document scored(Document chunk) {
        return Document.builder().id(chunk.getId()).text(chunk.getText()).metadata(chunk.getMetadata()).score(0.99).build();
    }

    private List<String> sources(KnowledgeEvidence evidence) {
        return evidence.chunks().stream().map(chunk -> (String) chunk.getMetadata().get("source_id")).distinct().toList();
    }

    @Test void articleResubmissionIsNotExplainedUsingOnlyCommentManagement() {
        when(index.search(anyString())).thenReturn(catalog.chunks().stream()
                .filter(chunk -> "help-comment-manage".equals(chunk.getMetadata().get("source_id"))).map(this::scored).toList());
        for (String question : List.of(
                "我的文章审核未通过，怎么修改后重新提交？",
                "文章被驳回后如何编辑并重新发布？")) {
            List<String> found = sources(service.retrieve(question));
            assertTrue(found.stream().anyMatch(id -> Set.of("help-article-edit", "help-article-manage", "help-article-visibility", "help-article-publish").contains(id)), question + found);
            assertNotEquals("help-comment-manage", found.getFirst(), question + found);
        }
        String ambiguous = "我想把被退回的内容改好再提交，并确认处理结果，有哪些页面和消息可以帮助我？";
        assertFalse(service.hasTopicAnchor(service.retrieve(ambiguous), ambiguous),
                "The original E04 has no unique business object; the semantic candidate must not be marked SUPPORTED.");
        verify(index, times(3)).search(anyString());
        verify(index, never()).upsert(anyList());
        verify(index, never()).deleteObsoleteChunks(anyList());
        assertEquals(0, catalog.epoch());
    }

    @Test void emptyOrUnavailableIndexCannotBeReplacedByLocalCatalogFacts() {
        when(index.search(anyString())).thenReturn(List.of());
        assertTrue(service.retrieve("发送私信与撤回消息").chunks().isEmpty());
        verify(index, never()).readChunks(anyList());
        when(index.search(anyString())).thenThrow(new RagFailure(RagFailure.Code.QDRANT_UNAVAILABLE));
        assertEquals(RagFailure.Code.QDRANT_UNAVAILABLE,
                assertThrows(RagFailure.class, () -> service.retrieve("发送私信与撤回消息")).code());
    }

    @Test void keywordRetrievalHandlesShortenedVerbsAndInterleavedDeterminers() {
        when(index.search(anyString())).thenReturn(catalog.chunks().stream()
                .filter(chunk -> "help-web-entry".equals(chunk.getMetadata().get("source_id"))).map(this::scored).toList());
        for (String question : List.of("怎么在网页端发文章？", "我想发布一篇文章，那在哪里打开？", "我要发布这篇文章")) {
            List<String> found = sources(service.retrieve(question));
            assertTrue(found.contains("help-article-publish"), question + found);
        }
        assertTrue(sources(service.retrieve("怎样撤回自己的一条私信？")).contains("help-private-revoke"));
    }

    @Test void actualKnowledgeKeywordsCannotLoseNegationWhenShortened() throws Exception {
        Set<String> actualKeywords = new HashSet<>();
        try (var input = getClass().getClassLoader().getResourceAsStream("knowledge/platform-help.json")) {
            for (KnowledgeDocument source : json.readValue(input, KnowledgeDocument[].class)) actualKeywords.addAll(source.keywords());
        }
        for (var example : Map.of("专栏不显示", "专栏显示", "评论不显示", "评论显示",
                "历史页面不存在", "历史页面存在", "我的评论没显示", "我的评论显示", "标记未读", "标记读").entrySet()) {
            assertTrue(actualKeywords.contains(example.getKey()), example.getKey());
            assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "containsKeyword", example.getKey(), example.getKey()));
            assertEquals(Boolean.FALSE, ReflectionTestUtils.invokeMethod(service, "containsKeyword", example.getValue(), example.getKey()), example.toString());
        }
        assertTrue(actualKeywords.contains("发布文章"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.invokeMethod(service, "containsKeyword", "怎么发文章", "发布文章"));
    }

    @Test void weakKeywordDoesNotDisplaceStrongSemanticEvidenceAndSynonymsAreNotDiscarded() {
        List<Document> candidates = catalog.chunks().stream()
                .filter(chunk -> Set.of("help-search", "help-article-publish").contains(chunk.getMetadata().get("source_id")))
                .map(chunk -> Document.builder().id(chunk.getId()).text(chunk.getText()).metadata(chunk.getMetadata())
                        .score("help-search".equals(chunk.getMetadata().get("source_id")) ? 0.99 : 0.70).build()).toList();
        when(index.search(anyString())).thenReturn(candidates);
        assertEquals("help-search", sources(service.retrieve("怎么按标签找内容？")).getFirst());
        for (var example : Map.of("忘了口令怎么找回？", "help-password-reset", "我想写一篇博客，从哪儿开始？", "help-article-publish",
                "之前发的博文去哪里改？", "help-article-edit", "我的稿子在哪个页面统一查看？", "help-article-manage").entrySet()) {
            when(index.search(anyString())).thenReturn(catalog.chunks().stream()
                    .filter(chunk -> example.getValue().equals(chunk.getMetadata().get("source_id"))).map(this::scored).toList());
            assertTrue(sources(service.retrieve(example.getKey())).contains(example.getValue()), example.getKey());
        }
    }

    @Test void supplementalSourceMustPassVersionAndPhysicalCompletenessChecks() {
        when(index.search(anyString())).thenReturn(catalog.chunks().stream()
                .filter(chunk -> "help-comment-manage".equals(chunk.getMetadata().get("source_id"))).map(this::scored).toList());
        when(index.readChunks(anyList())).thenReturn(List.of());
        assertEquals(RagFailure.Code.INDEX_MISMATCH,
                assertThrows(RagFailure.class, () -> service.retrieve("文章审核未通过后修改文章重新提交")).code());
        when(index.readChunks(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return catalog.chunks().stream().filter(chunk -> ids.contains(chunk.getId())).map(chunk -> {
                Map<String, Object> forged = new LinkedHashMap<>(chunk.getMetadata());
                forged.put("version", "stale-version");
                return new Document(chunk.getId(), "FORGED_BODY", forged);
            }).toList();
        });
        assertEquals(RagFailure.Code.INDEX_MISMATCH,
                assertThrows(RagFailure.class, () -> service.retrieve("文章审核未通过后修改文章重新提交")).code());
    }

    @Test void frozenQa40RecordsOfflineRecallAndAlwaysPreservesSourceAuthority() throws Exception {
        when(index.search(anyString())).thenReturn(catalog.chunks().stream().map(this::scored).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        int applicable = 0;
        int hits = 0;
        try (var input = getClass().getClassLoader().getResourceAsStream("quality/qa40-regression.json")) {
            var fixture = json.readTree(input);
            assertEquals("3dd3e676ee24b5c830f071b5578c4f6165af85820b077e7ea7054b6db51f61e4", fixture.path("originalSha256").asText());
            assertEquals(40, fixture.path("cases").size());
            for (var question : fixture.path("cases")) {
                StringBuilder query = new StringBuilder();
                for (var history : question.path("history")) {
                    if ("user".equals(history.path("role").asText())) query.append(history.path("content").asText()).append(' ');
                }
                query.append(question.path("question").asText());
                KnowledgeEvidence evidence = service.retrieve(query.toString());
                List<String> found = sources(evidence);
                assertTrue(PublicationManifest.userSourceIds().containsAll(found));
                assertTrue(found.size() <= 4);
                for (Document chunk : evidence.chunks()) assertEquals(catalog.verified(chunk).getText(), chunk.getText());
                List<String> expected = new ArrayList<>();
                question.path("expected_source").forEach(id -> expected.add(id.asText()));
                boolean measured = !expected.isEmpty();
                boolean hit = measured && found.stream().anyMatch(expected::contains);
                if (measured) { applicable++; if (hit) hits++; }
                rows.add(Map.of("id", question.path("id").asText(), "expectedSources", expected, "retrievedSources", found,
                        "measuredRecall", measured, "hitAt4", hit));
            }
        }
        System.out.println("QA40_OFFLINE_REPLAY=" + json.writeValueAsString(Map.of("mode", "SYNTHETIC_DENSE_TIES_NOT_REAL_SEMANTIC_QUALITY",
                "cases", rows, "applicable", applicable, "hitAt4", hits, "generatedAnswers", 0, "realProviderCalls", 0)));
        assertEquals(40, rows.size());
        verify(index, never()).upsert(anyList());
        verify(index, never()).deleteObsoleteChunks(anyList());
    }
}
