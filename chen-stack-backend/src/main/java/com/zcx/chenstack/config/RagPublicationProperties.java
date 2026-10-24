package com.zcx.chenstack.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立的外部发布审批输入；没有默认批准文件或密钥。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "chen.rag.release")
public class RagPublicationProperties {
    private boolean enabled = false;
    private String manifestPath = "";
    private String approvalPath = "";
    private String publicKeyPath = "";
    private String operator = "";
    private String environment = "";
    private String artifactPath = "";
}
