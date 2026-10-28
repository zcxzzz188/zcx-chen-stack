package com.zcx.chenstack.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chen.wiki.compiler")
public record WikiProviderProperties(String provider, String mockBaseUrl, String authorizationPath,
                                     String authorizationSha256, String budgetLedgerPath, String budgetId) {
    public WikiProviderProperties {
        provider = provider == null ? "none" : provider;
        if (!java.util.Set.of("none", "mock", "deepseek").contains(provider)) throw new IllegalArgumentException("WIKI_PROVIDER_INVALID");
    }
}
