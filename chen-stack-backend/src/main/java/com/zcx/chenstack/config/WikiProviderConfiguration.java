package com.zcx.chenstack.config;

import com.zcx.chenstack.wiki.*;
import com.zcx.chenstack.rag.NormalAiUsage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/** 构造不发请求；正式持续计量与历史验收授权显式分开。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "chen.wiki", name = {"enabled", "compiler.enabled"}, havingValue = "true")
@EnableConfigurationProperties(WikiProviderProperties.class)
@lombok.extern.slf4j.Slf4j
public class WikiProviderConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WikiModel.class)
    @ConditionalOnProperty(prefix = "chen.wiki.compiler", name = "provider", havingValue = "mock")
    public SpringAiWikiModel mockWikiModel(WikiProviderProperties properties) {
        return SpringAiWikiModel.loopback(URI.create(properties.mockBaseUrl()), "wiki-mock");
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(WikiModel.class)
    @ConditionalOnProperty(prefix = "chen.wiki.compiler", name = "provider", havingValue = "deepseek")
    public SpringAiWikiModel deepseekWikiModel(WikiProviderProperties properties,
            ObjectProvider<NormalAiUsage> normalUsage,
            @Value("${spring.ai.openai.base-url}") String base,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            @Value("${spring.ai.openai.chat.completions-path:/chat/completions}") String completionsPath,
            @Value("${chen.rag.release.budget-ledger-path:}") String g2Ledger) {
        URI endpoint = URI.create(base.replaceAll("/+$", "") + completionsPath);
        NormalAiUsage usage = normalUsage.getIfAvailable();
        if (usage != null && usage.enabled()) {
            SpringAiWikiModel result = SpringAiWikiModel.continuousDeepseek(endpoint, model, apiKey, usage);
            log.info("Wiki Provider已装配：deepseek；正式持续使用并独立计量编译请求，未发送模型请求");
            return result;
        }
        WikiProviderAuthorization authorization = new WikiProviderAuthorization(properties.authorizationPath(), properties.authorizationSha256(),
                properties.budgetLedgerPath(), properties.budgetId(), g2Ledger, endpoint, model);
        SpringAiWikiModel result = SpringAiWikiModel.deepseek(endpoint, model, apiKey, authorization);
        log.info("Wiki Provider已装配：deepseek；未获独立授权时禁止发送，未初始化账本或任务");
        return result;
    }
}
