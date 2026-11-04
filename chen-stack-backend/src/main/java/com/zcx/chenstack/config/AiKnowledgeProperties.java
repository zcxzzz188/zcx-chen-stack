package com.zcx.chenstack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署默认知识模式；管理员可在后台改为 rag、wiki 或 hybrid。
 *
 * <p>chat 只作为历史配置值继续可读：它不再是可选的产品模式，但旧环境变量或旧审计记录
 * 不能被当成非法值丢弃。请求本身仍然无法修改知识或工具权限。</p>
 */
@ConfigurationProperties(prefix = "chen.ai")
public record AiKnowledgeProperties(String knowledgeMode) {
    public AiKnowledgeProperties {
        knowledgeMode = knowledgeMode == null ? "rag" : knowledgeMode;
        switch (knowledgeMode) {
            case "rag", "wiki", "hybrid" -> { }
            case "chat" -> { }
            case "auto" -> throw new IllegalArgumentException("AI_KNOWLEDGE_MODE: 请使用 hybrid，auto 不是有效值");
            default -> throw new IllegalArgumentException("AI_KNOWLEDGE_MODE 无效：仅支持 rag/wiki/hybrid（chat 仅兼容历史配置）");
        }
    }
}
