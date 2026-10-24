package com.zcx.chenstack.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 默认关闭的本地运维输入；普通读取恢复仅需Manifest和持久完成记录。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chen.rag.release")
public class RagPublicationProperties {
    private boolean enabled = false;
    private boolean recoveryEnabled = false;
    private String manifestPath = "";
    private String operationPath = "";
    private String operator = "";
    private String environment = "";
    private String artifactPath = "";
    private String budgetLedgerPath = "";
    private String budgetId = "";
    private int maxEmbeddingHttpAttempts = 64;
    private int maxChatHttpAttempts = 20;
}
