package com.zcx.chenstack.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeDocumentTopicsTest {
    @Test
    void markdownPreservesIntroductionNestedHeadingsAndTailBeyondOldDocumentLimit() {
        String middle = "步骤包含权限限制。".repeat(1300);
        String tail = "需要保留文末异常条件。".repeat(1050);
        String source = "# 业务手册\n简介完整。\n## 文章\n" + middle
                + "\n~~~md\n## 示例标题\n~~~\n### 限制\n保留条件。\n## 消息\n" + tail;
        assertTrue(source.length() > KnowledgeChunker.MAX_DOCUMENT_CHARS);
        var generator = mock(KnowledgeWikiGenerator.class);
        var document = parse("manual.md", source, generator);
        assertEquals(List.of("业务手册", "文章", "消息"), document.topics().stream().map(KnowledgeDocumentTopics.Topic::title).toList());
        assertEquals("简介完整。", document.topics().get(0).content());
        assertEquals(middle + "\n~~~md\n## 示例标题\n~~~\n### 限制\n保留条件。", document.topics().get(1).content());
        assertEquals(tail, document.topics().getLast().content());
        verifyNoInteractions(generator);
    }

    @Test
    void curatedAnchorsKeepIdsAndLiteralNestedHeadingsAcrossEdits() {
        var topics = List.of(new KnowledgeDocumentTopics.Topic(42L, "账号", "使用绑定邮箱。\n## 正文中的示例\n禁止分享验证码。"),
                new KnowledgeDocumentTopics.Topic(57L, "私信", "仅本人能看。"));
        byte[] source = KnowledgeDocumentTopics.render("平台手册", topics);
        assertEquals(topics, KnowledgeDocumentTopics.parse("master.md", source, true, null).topics());
    }

    @Test
    void textUsesBoundariesWithoutRewritingAnyParagraph() {
        List<String> paragraphs = List.of("文章先保存草稿。", "发布须等待审核。", "私信只可撤回自己的消息。");
        var boundaries = new KnowledgeWikiGenerator.TopicBoundary[]{
                new KnowledgeWikiGenerator.TopicBoundary("文章", 2), new KnowledgeWikiGenerator.TopicBoundary("私信", 3)};
        var generator = mock(KnowledgeWikiGenerator.class);
        when(generator.split(paragraphs)).thenReturn(KnowledgeWikiGenerator.applyBoundaries(paragraphs, boundaries));
        var parsed = parse("manual.txt", String.join("\n\n", paragraphs), generator);
        assertEquals(2, parsed.topics().size());
        assertEquals(String.join("\n\n", paragraphs),
                String.join("\n\n", parsed.topics().stream().map(KnowledgeDocumentTopics.Topic::content).toList()));
        boundaries[1] = new KnowledgeWikiGenerator.TopicBoundary("私信", 2);
        assertTrue(KnowledgeWikiGenerator.applyBoundaries(paragraphs, boundaries).isEmpty());
    }

    @Test
    void numberedTextChaptersNeedNoModelCall() {
        var generator = mock(KnowledgeWikiGenerator.class);
        var parsed = parse("help.txt", "一、账号\n保留原邮箱限制。\n二、私信\n保留撤回时限。", generator);
        assertEquals(List.of("账号", "私信"), parsed.topics().stream().map(KnowledgeDocumentTopics.Topic::title).toList());
        verifyNoInteractions(generator);
    }

    private static KnowledgeDocumentTopics.Document parse(String name, String source, KnowledgeWikiGenerator generator) {
        return KnowledgeDocumentTopics.parse(name, source.getBytes(StandardCharsets.UTF_8), name.endsWith(".md"), generator);
    }
}
