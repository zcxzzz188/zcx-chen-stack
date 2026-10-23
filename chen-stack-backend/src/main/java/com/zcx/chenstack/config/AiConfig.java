package com.zcx.chenstack.config;

import com.zcx.chenstack.domain.constants.AiPromptConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * AI 配置类
 * 配置 DeepSeek API（使用 OpenAI 兼容接口）
 */
@Configuration
public class AiConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Value("${spring.ai.openai.chat.options.temperature}")
    private Double temperature;

    @Value("${spring.ai.openai.chat.options.max-tokens}")
    private Integer maxTokens;

    @Value("${spring.ai.openai.chat.completions-path:/chat/completions}")
    private String completionsPath;

    /**
     * 配置 OpenAI Chat 模型（使用 DeepSeek API）
     * 使用自定义配置连接 DeepSeek API
     */
    @Bean
    public OpenAiChatModel openAiChatModel() {
        if (maxTokens == null || maxTokens < 1 || maxTokens > 393216) {
            throw new IllegalArgumentException("spring.ai.openai.chat.options.max-tokens 必须为 1 到 393216 的整数");
        }
        // 创建 OpenAI API 客户端，指向 DeepSeek API 地址
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .completionsPath(completionsPath)
                .apiKey(apiKey)
                .build();

        // 创建聊天选项（使用新的 API 方式）
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(model);
        options.setTemperature(temperature);
        options.setMaxTokens(maxTokens);
        options.setExtraBody(Map.of("thinking", Map.of("type", "disabled")));

        // 创建并返回 OpenAI Chat 模型
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                // 失败由调用方处理，避免自动重发产生重复计费请求。
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
    }

    /**
     * 配置会话记忆存储（用于智能客服）
     * 使用内存存储，实际生产环境建议使用 Redis
     */
    @Bean
    public ChatMemory chatMemory() {
        // M5 的 Advisor 每次读取最近 100 条消息，避免使用新版默认的 20 条窗口。
        return MessageWindowChatMemory.builder().maxMessages(100).build();
    }

    /**
     * 配置写作助手专用的 ChatClient
     * 专注于帮助用户进行内容创作
     */
    @Bean
    public ChatClient writingAssistantChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem(AiPromptConstants.WRITING_ASSISTANT_SYSTEM_PROMPT)
                .build();
    }
}
