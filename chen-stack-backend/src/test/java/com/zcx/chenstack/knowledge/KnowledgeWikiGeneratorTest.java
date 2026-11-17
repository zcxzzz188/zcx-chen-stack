package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeWikiGeneratorTest {
    private static final String BOUNDARIES = """
            [{"title":"文章","end":1},{"title":"评论","end":2}]
            """.strip();

    @Test
    void acceptsJsonFencesWithoutChangingAnySourceParagraph() {
        List<String> paragraphs = List.of("仅本人可见。", "审核通过后公开。");
        for (String output : List.of(BOUNDARIES, "```json\n" + BOUNDARIES + "\n```",
                "```JSON\r\n" + BOUNDARIES + "\r\n```", "```\n" + BOUNDARIES + "\n```")) {
            var topics = generator(output).split(paragraphs);
            assertEquals(paragraphs, topics.stream().map(KnowledgeDocumentTopics.Topic::content).toList());
        }
    }

    @Test
    void rejectsIncompleteOrExtraneousModelOutput() {
        for (String output : List.of("[", BOUNDARIES + " {}", "```json\n" + BOUNDARIES + "\n```\n解释")) {
            var error = assertThrows(KnowledgeDocException.class,
                    () -> generator(output).split(List.of("仅本人可见。", "审核通过后公开。")));
            assertEquals("KNOWLEDGE_SPLIT_UNAVAILABLE", error.code());
        }
    }

    @Test
    void multiSourceWikiRequiresAnExcerptFromEveryLinkedSource() {
        var sources = List.of(new KnowledgeWikiGenerator.Source("评论审核", "待审评论仅作者可见。"),
                new KnowledgeWikiGenerator.Source("文章草稿", "先保存草稿，再从文章管理继续编辑。"));
        var incomplete = new KnowledgeWikiGenerator.Extraction("评论审核", "待审评论仅作者可见。",
                List.of("待审评论仅作者可见。"));
        assertThrows(KnowledgeDocException.class, () -> KnowledgeWikiGenerator.verify(incomplete, sources));
        var complete = new KnowledgeWikiGenerator.Extraction("评论审核", "待审评论仅作者可见。",
                List.of("待审评论仅作者可见。", "先保存草稿，再从文章管理继续编辑。"));
        assertEquals("待审评论仅作者可见。\n\n先保存草稿，再从文章管理继续编辑。",
                KnowledgeWikiGenerator.verify(complete, sources).content());
    }

    private static KnowledgeWikiGenerator generator(String output) {
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
        KnowledgeSettings settings = mock(KnowledgeSettings.class);
        when(settings.get()).thenReturn(new KnowledgeSettings.Settings("hybrid", "test-model", 0, 1024, 5, 100, "test"));
        return new KnowledgeWikiGenerator(new ObjectMapper(), model, settings);
    }
}
