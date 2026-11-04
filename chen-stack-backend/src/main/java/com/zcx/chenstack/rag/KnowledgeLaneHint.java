package com.zcx.chenstack.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能混合的本地知识路径提示：只做字符重叠打分，不产生任何 Provider 调用。
 *
 * <p>提示只影响模型先读哪一侧，不强制、也不排斥另一侧；两侧都可能有候选时明确要求
 * 先读最贴合的一个。因此提示错误最多多读一次正文，不会隐藏可用的知识来源。</p>
 */
final class KnowledgeLaneHint {
    /** 标题或主题词与问题达到该重叠比例才认为存在明确候选。 */
    static final double STRONG = 0.4;
    private static final int MAX_TITLE = 60;

    record Candidate(String title, double score) {
        static final Candidate NONE = new Candidate("", 0);
    }

    private KnowledgeLaneHint() { }

    static Candidate best(String question, List<String> titles) {
        Candidate best = Candidate.NONE;
        if (question == null || titles == null) return best;
        for (String title : titles) {
            if (title == null || title.isBlank()) continue;
            double score = score(question, title);
            if (score > best.score()) best = new Candidate(title, score);
        }
        return best;
    }

    static double score(String question, String title) {
        String query = normalize(question);
        String topic = normalize(title);
        if (query.isEmpty() || topic.length() < 2) return 0;
        if (query.contains(topic)) return 1;
        Set<String> queryGrams = bigrams(query);
        Set<String> topicGrams = bigrams(topic);
        if (topicGrams.isEmpty()) return 0;
        int hit = 0;
        for (String gram : topicGrams) if (queryGrams.contains(gram)) hit++;
        // 至少命中两个二字片段，避免单个字的偶然重叠被当成候选。
        return hit >= 2 ? (double) hit / topicGrams.size() : 0;
    }

    /** 提供给模型的单行提示；永远不声称已经读过正文。 */
    static String describe(String question, List<String> ragTitles, List<String> wikiTitles) {
        Candidate rag = best(question, limit(ragTitles));
        Candidate wiki = best(question, limit(wikiTitles));
        boolean ragStrong = rag.score() >= STRONG;
        boolean wikiStrong = wiki.score() >= STRONG;
        if (wikiStrong && !ragStrong) {
            return "本轮初步相关：Wiki《" + wiki.title() + "》。优先核对这一侧的已发布正文；只有需要规则、限制等原始事实时才再查 RAG。";
        }
        if (ragStrong && !wikiStrong) {
            return "本轮初步相关：RAG《" + rag.title() + "》。优先读取这一侧的主题正文；只有需要完整操作步骤时才再查 Wiki。";
        }
        if (ragStrong) {
            return "本轮两侧都有候选：Wiki《" + wiki.title() + "》、RAG《" + rag.title() + "》。先读最贴合的一个，证据不足再补另一侧，不要无差别同时检索。";
        }
        return "本轮两侧没有明确的标题候选：先用一次语义检索（searchPlatformHelp 或 searchPublishedWiki）定位，仍不足再查另一侧。";
    }

    private static List<String> limit(List<String> titles) {
        if (titles == null) return List.of();
        return titles.size() <= MAX_TITLE ? titles : titles.subList(0, MAX_TITLE);
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isLetterOrDigit(value)) out.append(Character.toLowerCase(value));
        }
        return out.toString();
    }

    private static Set<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        for (int index = 0; index + 1 < text.length(); index++) grams.add(text.substring(index, index + 2));
        return grams;
    }
}
