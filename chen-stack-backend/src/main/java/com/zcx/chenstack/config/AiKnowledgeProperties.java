package com.zcx.chenstack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fixed server-side mode; a request cannot change the knowledge or tool authority. */
@ConfigurationProperties(prefix = "chen.ai")
public record AiKnowledgeProperties(String knowledgeMode) {
    public AiKnowledgeProperties {
        knowledgeMode = knowledgeMode == null ? "rag" : knowledgeMode;
        switch (knowledgeMode) {
            case "chat", "rag", "wiki" -> { }
            case "hybrid", "auto" -> throw new IllegalArgumentException("AI_KNOWLEDGE_MODE: hybrid/auto 尚未实现，不能启用");
            default -> throw new IllegalArgumentException("AI_KNOWLEDGE_MODE 无效：仅支持 chat/rag/wiki");
        }
    }
}
