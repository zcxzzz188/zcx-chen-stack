package com.zcx.chenstack.config;

import com.zcx.chenstack.domain.constants.AiPromptConstants;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public OpenAiChatModel openAiChatModel(ReleaseProviderBudget budget) {
        if (maxTokens == null || maxTokens < 1 || maxTokens > 393216) {
            throw new IllegalArgumentException("spring.ai.openai.chat.options.max-tokens 必须为 1 到 393216 的整数");
        }
        var clientOptions = com.openai.core.ClientOptions.builder().baseUrl(baseUrl).apiKey(apiKey).maxRetries(0)
                .httpClient(budget.httpClient(ReleaseProviderBudget.Kind.CHAT, java.time.Duration.ofSeconds(60),
                        baseUrl.replaceAll("/+$", "") + completionsPath)).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).temperature(temperature)
                .maxTokens(maxTokens).maxRetries(0).extraBody(Map.of("thinking", Map.of("type", "disabled"))).build();
        return OpenAiChatModel.builder()
                .openAiClient(new com.openai.client.OpenAIClientImpl(clientOptions))
                .openAiClientAsync(new com.openai.client.OpenAIClientAsyncImpl(clientOptions))
                .options(options)
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
