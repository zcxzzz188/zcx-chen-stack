package com.zcx.chenstack.knowledge;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.Map;

/**
 * 复用应用现有的 Spring AI DeepSeek ChatModel，不新建 HTTP 客户端、不新建第二套模型配置。
 *
 * <p>分析请求与普通对话共用同一条 {@code ReleaseProviderBudget} 传输链，因此正常计量、
 * 重试禁止、超时与失败记录都沿用现有机制。</p>
 */
public final class SpringAiKnowledgeExtractionModel implements KnowledgeExtractionModel {
    private final OpenAiChatModel model;
    private final String modelId;

    public SpringAiKnowledgeExtractionModel(OpenAiChatModel model, String modelId) {
        if (model == null || modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("KNOWLEDGE_EXTRACTION_MODEL_REQUIRED");
        }
        this.model = model;
        this.modelId = modelId;
    }

    @Override
    public String id() {
        return modelId;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt, int maxTokens) {
        if (systemPrompt == null || systemPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()
                || maxTokens < 1 || maxTokens > 8192) {
            throw KnowledgeDocException.bad("AI_ANALYSIS_REQUEST_INVALID", "AI分析请求参数无效");
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelId)
                .temperature(0.0)
                .maxTokens(maxTokens)
                .toolCallbacks(List.of())
                .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                .build();
        try {
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt), new UserMessage(userPrompt)), options));
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                throw KnowledgeDocException.unavailable("AI_ANALYSIS_FAILED", "AI分析未返回可用结果");
            }
            String finishReason = response.getResult().getMetadata() == null ? null
                    : response.getResult().getMetadata().getFinishReason();
            if (finishReason != null && !"stop".equalsIgnoreCase(finishReason)) {
                throw KnowledgeDocException.unavailable("AI_ANALYSIS_INCOMPLETE",
                        "AI分析结果未完整生成，请重试");
            }
            String text = response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw KnowledgeDocException.unavailable("AI_ANALYSIS_FAILED", "AI分析未返回可用结果");
            }
            return text;
        } catch (KnowledgeDocException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 不把 Provider 的原始异常、URL 或密钥带回接口。
            throw KnowledgeDocException.unavailable("AI_ANALYSIS_FAILED",
                    "AI分析服务暂时不可用，文件已保留，可稍后重试");
        }
    }
}
