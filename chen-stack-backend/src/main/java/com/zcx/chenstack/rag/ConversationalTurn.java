package com.zcx.chenstack.rag;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 纯寒暄、致谢和自我介绍类话语的确定性识别。
 *
 * <p>只用于在智能混合模式下省掉一次范围分类请求。识别集合是封闭白名单：任何可能包含
 * 后台、管理、他人数据或平台规则诉求的话语都不会命中，因此跳过分类不会放宽边界。
 * 命中后仍走同一条回答通道，业务工具与后台拒绝规则保持不变。</p>
 */
final class ConversationalTurn {
    private static final int MAX_LENGTH = 24;
    private static final Set<String> UTTERANCES = Set.of(
            "你好", "您好", "嗨", "哈喽", "哈啰", "在吗", "在么", "在不在",
            "早上好", "上午好", "中午好", "下午好", "晚上好", "早安", "晚安",
            "谢谢", "谢谢你", "谢谢啦", "多谢", "感谢", "感谢你", "辛苦了",
            "不客气", "再见", "拜拜", "回头见", "好的", "好嘞", "嗯", "嗯嗯", "收到", "明白",
            "ok", "okay", "hi", "hello", "hey", "thanks", "thankyou", "bye",
            "你是谁", "你叫什么", "你叫什么名字", "你是机器人吗", "你是ai吗", "你是人工智能吗");

    private ConversationalTurn() { }

    static boolean isPureSocial(String question) {
        if (question == null) return false;
        String text = question.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) return false;
        boolean matched = false;
        for (String segment : text.split("[\\s，。！？、,.!?~～;；:：…\\-]+")) {
            if (segment.isEmpty()) continue;
            matched = true;
            if (!UTTERANCES.contains(segment.toLowerCase(Locale.ROOT))) return false;
        }
        return matched;
    }

    static List<String> utterances() { return List.copyOf(UTTERANCES); }
}
