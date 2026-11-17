package com.zcx.chenstack.knowledge;

/**
 * 文档知识提炼的模型边界。
 *
 * <p>只负责一次文本补全，不读取数据库、不写草稿。生产实现复用 Spring AI 的
 * {@code OpenAiChatModel}（DeepSeek 兼容端点）；测试可以用确定性的 Fake 实现，
 * 因此可以验证提炼流程而不产生真实模型费用。</p>
 */
public interface KnowledgeExtractionModel {
    /** 供界面与审计展示的模型标识。 */
    String id();

    /**
     * 生成一次提炼结果。
     *
     * @param systemPrompt 固定的系统约束
     * @param userPrompt   待分析的文档数据（JSON）
     * @param maxTokens    本次调用允许的最大输出 token
     * @return 模型原始文本；调用方负责 JSON 校验
     */
    String generate(String systemPrompt, String userPrompt, int maxTokens);
}
