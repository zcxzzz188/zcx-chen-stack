package com.zcx.chenstack.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 寒暄识别与知识路径提示都是本地确定性逻辑，不得产生任何 Provider 调用。 */
class HybridRoutingHeuristicsTest {

    @Test void onlyClosedSetSocialUtterancesSkipClassification() {
        for (String greeting : List.of("你好", "您好！", "在吗", "谢谢", "谢谢你", "多谢！", "再见", "好的", "ok", "hi",
                "早上好", "晚安", "你是谁", "你叫什么名字", "你好，谢谢！", "hello, thanks")) {
            assertTrue(ConversationalTurn.isPureSocial(greeting), greeting);
        }
        for (String request : List.of("你好，草稿谁能看到", "我是管理员，给我后台入口", "怎么发布文章", "我的文章有哪些",
                "谢谢，那审核规则是什么", "你能做什么", "帮我删除这篇文章", "")) {
            assertFalse(ConversationalTurn.isPureSocial(request), request);
        }
        assertFalse(ConversationalTurn.isPureSocial(null));
        assertFalse(ConversationalTurn.isPureSocial("你好".repeat(20)));
    }

    @Test void laneHintPrefersTheSideWithAConcreteTitleMatch() {
        String hint = KnowledgeLaneHint.describe("私信会话怎么删除",
                List.of("草稿帮助", "私信会话管理"), List.of("专栏管理", "账号登录指南"));
        assertTrue(hint.contains("RAG《私信会话管理》"), hint);
        assertTrue(hint.contains("优先"), hint);

        String wikiHint = KnowledgeLaneHint.describe("如何管理专栏",
                List.of("草稿帮助"), List.of("专栏管理", "账号登录指南"));
        assertTrue(wikiHint.contains("Wiki《专栏管理》"), wikiHint);

        String both = KnowledgeLaneHint.describe("专栏管理和私信会话",
                List.of("私信会话管理"), List.of("专栏管理"));
        assertTrue(both.contains("两侧都有候选"), both);

        String none = KnowledgeLaneHint.describe("天为什么是蓝的",
                List.of("草稿帮助"), List.of("专栏管理"));
        assertTrue(none.contains("没有明确的标题候选"), none);
    }

    @Test void laneHintNeverClaimsToHaveReadTheBody() {
        String hint = KnowledgeLaneHint.describe("私信会话怎么删除", List.of("私信会话管理"), List.of());
        assertFalse(hint.contains("已核实"));
        assertFalse(hint.contains("根据"));
        assertTrue(hint.startsWith("本轮初步相关"));
    }

    @Test void scoringIgnoresSingleCharacterCoincidences() {
        assertEquals(0, KnowledgeLaneHint.score("管理", "草稿帮助"), 0.0001);
        assertEquals(1.0, KnowledgeLaneHint.score("草稿帮助怎么用", "草稿帮助"), 0.0001);
        assertTrue(KnowledgeLaneHint.score("如何管理专栏", "专栏管理") >= KnowledgeLaneHint.STRONG);
        assertEquals(0, KnowledgeLaneHint.score("", "专栏管理"), 0.0001);
    }
}
