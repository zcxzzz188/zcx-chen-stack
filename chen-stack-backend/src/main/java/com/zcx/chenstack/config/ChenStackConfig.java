package com.zcx.chenstack.config;

import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "chen-stack")
@Setter
public class ChenStackConfig {

    private List<String> allowOrigins;
    private Boolean photoAutoAudit;
    private Boolean articleAutoAudit;
    private Boolean columnAutoAudit;
    private Boolean commentAutoAudit;

    public List<String> getAllowOrigins() {
        return this.allowOrigins;
    }

    public boolean isPhotoAutoAudit() {
        return Boolean.TRUE.equals(this.photoAutoAudit);
    }

    public boolean isArticleAutoAudit() {
        return Boolean.TRUE.equals(this.articleAutoAudit);
    }

    public boolean isColumnAutoAudit() {
        return Boolean.TRUE.equals(this.columnAutoAudit);
    }

    public boolean isCommentAutoAudit() {
        return Boolean.TRUE.equals(this.commentAutoAudit);
    }

}
