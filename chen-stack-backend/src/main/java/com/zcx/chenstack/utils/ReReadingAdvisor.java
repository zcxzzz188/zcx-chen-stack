package com.zcx.chenstack.utils;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Re-Reading Advisor (Re2)
 * <p>
 * 通过让模型重新阅读问题来提高推理能力。原理出自论文 arXiv:2309.06275。
 * <p>
 * 核心思想：将用户输入改写为 "{Input_Query}\nRead the question again: {Input_Query}"
 * 强制模型在生成答案前重新审视问题，减少误解，提高复杂推理的准确率。
 * <p>
 * 注意：输入长度翻倍，API 调用成本也会翻倍，请根据场景权衡使用。
 */
public class ReReadingAdvisor implements BaseAdvisor {

    private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
            {re2_input_query}
            Read the question again: {re2_input_query}
            """;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        return request.mutate()
                .prompt(request.prompt().augmentUserMessage(user -> user.mutate()
                        .text(DEFAULT_RE2_ADVISE_TEMPLATE.replace("{re2_input_query}", user.getText()))
                        .build()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse advisedResponse, AdvisorChain advisorChain) {
        // 不修改响应，直接返回
        return advisedResponse;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        // 设置为高优先级，确保在 MessageChatMemoryAdvisor 之前执行
        return 0;
    }
}
