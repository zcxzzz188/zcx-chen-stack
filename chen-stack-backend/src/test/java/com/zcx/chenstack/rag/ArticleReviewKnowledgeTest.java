package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 源码资源与合成索引的离线检查，不代表真实语义召回或模型回答质量。 */
class ArticleReviewKnowledgeTest {
    @Test void reviewQuestionsReceiveCompleteArticleFactsThroughExistingRetrieval() throws Exception {
        KnowledgeCatalog catalog;
        try (var input = getClass().getResourceAsStream("/knowledge/platform-help.json")) {
            catalog = new KnowledgeCatalog(Arrays.stream(new ObjectMapper().findAndRegisterModules()
                    .readValue(input, KnowledgeDocument[].class))
                    .filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                    .map(d -> new KnowledgeDocument(d.sourceId(), d.title(), d.text(), d.version(), d.sourceType(),
                            d.sourceUrl(), "PUBLIC", "DOCUMENT_REVIEWED", d.updatedAt(), d.keywords())).toList());
        }
        QdrantKnowledgeIndex index = mock(QdrantKnowledgeIndex.class);
        when(index.search(anyString())).thenReturn(catalog.chunks().stream()
                .filter(d -> "help-article-manage".equals(d.getMetadata().get("source_id")))
                .map(d -> Document.builder().id(d.getId()).text(d.getText()).metadata(d.getMetadata()).score(0.99).build()).toList());
        when(index.readChunks(anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0);
            return catalog.chunks().stream().filter(d -> ids.contains(d.getId())).toList();
        });
        RagProperties properties = new RagProperties(); properties.setEnabled(true);
        RagKnowledgeService service = new RagKnowledgeService(properties, catalog, index, null);
        for (String question : List.of("文章一般为什么审核不过？", "阿里云审核通过后还要人工审核吗？",
                "什么内容可能被拒绝？", "被拒绝后如何修改重新提交？", "哪里查看审核原因？",
                "文章自动审核和人工审核有什么区别？",
                "我发布的文章审核没有通过，应该在哪里查看审核结果？怎样修改后重新提交？请给我进入文章管理页面的按钮。")) {
            KnowledgeEvidence evidence = service.retrieve(question);
            assertTrue(evidence.chunks().stream().anyMatch(d -> "help-article-manage".equals(d.getMetadata().get("source_id"))), question);
            assertTrue(evidence.context().contains("阿里云内容自动审核"), question);
            assertTrue(evidence.context().contains("消息中心"), question);
        }
        KnowledgeDocument review = catalog.source("help-article-manage");
        for (String required : List.of("并非每篇都必须再人工审核", "命中类别就必然拒绝", "检测建议与实际审核结果",
                "不保证每条通知都有具体原因", "没有读取私人审核记录或通知正文的权限", "不能知道或编造某篇文章",
                "评论、专栏有各自流程", "重新发布不代表立即公开", "自动审核异常或未开启", "文字或图片检测不通过")) {
            assertTrue(review.text().contains(required), required);
        }
        assertFalse(review.text().contains("广告"));
        assertEquals("/creation/articlemanage", review.sourceUrl());
        assertEquals("管理自己的文章", review.title());
        assertEquals(39, catalog.sourceIds().size());
        assertFalse(catalog.sourceIds().stream().anyMatch(id -> id.startsWith("help-admin-") || id.equals("help-history")));
        assertTrue(service.retrieveByTitle("后台文章审核").chunks().isEmpty());
        verify(index, never()).upsert(anyList());
        verify(index, never()).deleteSource(anyString());
    }
}
