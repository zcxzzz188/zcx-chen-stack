package com.zcx.chenstack.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AI 候选知识提取的离线契约测试；模型调用被 Fake 替换，不产生任何真实费用。 */
class KnowledgeCandidateExtractorTest {
    private static final String DOC = """
            Redis 缓存穿透
            缓存穿透是指查询不存在的数据，请求绕过缓存直接访问数据库。解决方法包括缓存空对象和布隆过滤器。

            Redis 缓存击穿
            热点 key 过期瞬间大量请求访问数据库。可以使用互斥锁或逻辑过期。

            Redis 过期键删除策略
            惰性删除在访问时检查过期时间；定期删除由后台任务抽样清理。
            """;

    @Test
    void extractsIndependentTopicsAsSeparateCandidates() {
        Fake model = new Fake("""
                {"candidates":[
                  {"title":"Redis 缓存穿透的原因与解决方法","content":"缓存穿透是查询不存在的数据，绕过缓存直接访问数据库。可用缓存空对象或布隆过滤器。","keywords":["缓存穿透","布隆过滤器"],"sourceSection":"Redis 缓存穿透","sourceExcerpt":"缓存穿透是指查询不存在的数据","confidence":0.92,"reviewHints":[]},
                  {"title":"Redis 缓存击穿的应对方案","content":"热点 key 过期瞬间大量请求访问数据库，可用互斥锁或逻辑过期。","keywords":["缓存击穿","互斥锁"],"sourceSection":"Redis 缓存击穿","sourceExcerpt":"热点 key 过期瞬间大量请求访问数据库","confidence":0.9,"reviewHints":[]},
                  {"title":"Redis 过期键删除策略","content":"惰性删除在访问时检查过期时间；定期删除由后台任务抽样清理。","keywords":["惰性删除","定期删除"],"sourceSection":"Redis 过期键删除策略","sourceExcerpt":"惰性删除在访问时检查过期时间","confidence":0.95,"reviewHints":[]}
                ]}
                """);
        KnowledgeCandidateExtractor.Extraction result = extractor(model).extract("Redis.md", "Redis", DOC);
        assertEquals(3, result.candidates().size());
        assertEquals("Redis 缓存穿透的原因与解决方法", result.candidates().getFirst().title());
        assertTrue(result.candidates().stream().allMatch(c -> "PENDING_REVIEW".equals(c.reviewStatus())));
        assertTrue(result.candidates().stream().allMatch(c -> c.candidateId().matches("[a-f0-9]{16}")));
        assertEquals(3, result.candidates().stream().map(KnowledgeCandidateExtractor.Candidate::candidateId).distinct().count());
        assertTrue(model.user.contains("\"task\":\"KNOWLEDGE_EXTRACT_V1\""));
        assertTrue(model.user.contains("Redis 过期键删除策略"));
        assertEquals(6144, model.maxTokens);
    }

    @Test
    void acceptsCodeFencedJsonAndProducesStableCandidateIds() {
        String json = """
                ```json
                {"candidates":[{"title":"标题","content":"正文","keywords":[],"confidence":1}]}
                ```
                """;
        KnowledgeCandidateExtractor.Extraction first = extractor(new Fake(json)).extract("a.md", "文档", "正文");
        KnowledgeCandidateExtractor.Extraction second = extractor(new Fake(json)).extract("a.md", "文档", "正文");
        assertEquals(first.candidates().getFirst().candidateId(), second.candidates().getFirst().candidateId());
    }

    @Test
    void deduplicatesSameContentAndMergesKeywords() {
        Fake model = new Fake("""
                {"candidates":[
                  {"title":"A","content":"相同的知识正文","keywords":["k1"],"sourceSection":"一"},
                  {"title":"B","content":"相同的知识正文","keywords":["k2"],"sourceSection":"二"}
                ]}
                """);
        KnowledgeCandidateExtractor.Extraction result = extractor(model).extract("a.md", "文档",
                "相同的知识正文；一；二");
        assertEquals(1, result.candidates().size());
        assertEquals(List.of("k1", "k2"), result.candidates().getFirst().keywords());
        assertEquals("一 / 二", result.candidates().getFirst().sourceSection());
    }

    @Test
    void rejectsInvalidJsonWithoutPretendingSuccess() {
        KnowledgeDocException error = assertThrows(KnowledgeDocException.class,
                () -> extractor(new Fake("这不是 JSON")).extract("a.md", "文档", "正文"));
        assertEquals("AI_ANALYSIS_INVALID_OUTPUT", error.code());
        assertEquals(503, error.status());
    }

    @Test
    void rejectsEmptyCandidatesInsteadOfCreatingEmptyDraft() {
        KnowledgeDocException error = assertThrows(KnowledgeDocException.class,
                () -> extractor(new Fake("{\"candidates\":[]}")).extract("a.md", "文档", "正文"));
        assertEquals("AI_ANALYSIS_EMPTY", error.code());
    }

    @Test
    void dropsExcerptThatIsNotInSourceAndAddsReviewHint() {
        Fake model = new Fake("""
                {"candidates":[{"title":"标题","content":"正文内容","sourceExcerpt":"模型编造的原文","reviewHints":[]}]}
                """);
        KnowledgeCandidateExtractor.Candidate candidate = extractor(model)
                .extract("a.md", "文档", "文档中的真实正文内容").candidates().getFirst();
        assertEquals("", candidate.sourceExcerpt());
        assertTrue(candidate.reviewHints().stream().anyMatch(h -> h.contains("摘录未能逐字匹配")));
    }

    @Test
    void fallsBackToSectionThenDocumentTitleWhenModelOmitsTitle() {
        KnowledgeCandidateExtractor.Candidate bySection = extractor(new Fake("""
                {"candidates":[{"title":"","content":"正文","sourceSection":"缓存雪崩"}]}
                """)).extract("a.md", "文档", "正文").candidates().getFirst();
        assertEquals("缓存雪崩", bySection.title());
        KnowledgeCandidateExtractor.Candidate byDocument = extractor(new Fake("""
                {"candidates":[{"content":"正文"}]}
                """)).extract("a.md", "Redis 文档", "正文").candidates().getFirst();
        assertEquals("Redis 文档", byDocument.title());
    }

    @Test
    void truncatesOversizeContentAndMarksReviewHint() {
        String content = "字".repeat(20001);
        Fake model = new Fake("{\"candidates\":[{\"title\":\"长文\",\"content\":\"" + content + "\"}]}");
        KnowledgeCandidateExtractor.Candidate candidate = extractor(model)
                .extract("a.md", "文档", "短正文").candidates().getFirst();
        assertEquals(20000, candidate.content().length());
        assertTrue(candidate.reviewHints().stream().anyMatch(h -> h.contains("已截断")));
    }

    @Test
    void cleansKeywordsAndCapsCandidateCount() {
        StringBuilder json = new StringBuilder("{\"candidates\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) json.append(',');
            json.append("{\"title\":\"标题").append(i).append("\",\"content\":\"正文").append(i)
                    .append("\",\"keywords\":[\" k1 \",\"\",\"k1\",\"k2\"]}");
        }
        json.append("]}");
        KnowledgeCandidateExtractor.Extraction result = extractor(new Fake(json.toString()), 3)
                .extract("a.md", "文档", "正文");
        assertEquals(3, result.candidates().size());
        assertTrue(result.candidatesTruncated());
        assertEquals(List.of("k1", "k2"), result.candidates().getFirst().keywords());
    }

    @Test
    void lowerConfidenceAddsReviewHint() {
        KnowledgeCandidateExtractor.Candidate candidate = extractor(new Fake("""
                {"candidates":[{"title":"不确定","content":"正文","confidence":0.3}]}
                """)).extract("a.md", "文档", "正文").candidates().getFirst();
        assertTrue(candidate.reviewHints().stream().anyMatch(h -> h.contains("置信度较低")));
    }

    private static KnowledgeCandidateExtractor extractor(Fake model) {
        return extractor(model, 8);
    }

    private static KnowledgeCandidateExtractor extractor(Fake model, int maxCandidates) {
        return new KnowledgeCandidateExtractor(model, maxCandidates, 20000, 6144);
    }

    private static final class Fake implements KnowledgeExtractionModel {
        private final String output;
        private String user;
        private int maxTokens;

        private Fake(String output) {
            this.output = output;
        }

        @Override
        public String id() {
            return "fake-deepseek";
        }

        @Override
        public String generate(String systemPrompt, String userPrompt, int maxTokens) {
            this.user = userPrompt;
            this.maxTokens = maxTokens;
            return output;
        }
    }
}
