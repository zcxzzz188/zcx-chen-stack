package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.config.RagPublicationConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.Map;

/** 仅从本地进程调用的非Web运维命令，不由Main扫描注册，也不加载业务定时任务。 */
@Import({DataSourceAutoConfiguration.class, JacksonAutoConfiguration.class, RagPublicationConfiguration.class})
@EnableConfigurationProperties(RagProperties.class)
public class RagPublicationCommand {
    public record Invocation(boolean localOnly) { }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RagPublicationCommand.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("spring.main.web-application-type", "none",
                "chen.rag.enabled", "true", "chen.rag.release.enabled", "true",
                "chen.rag.release.recovery-enabled", "false"));
        application.addInitializers(context -> {
            if (context instanceof WebServerApplicationContext) {
                throw new SecurityException("PUBLICATION_COMMAND_MUST_NOT_START_HTTP");
            }
        });
        try (var context = application.run(args)) {
            context.getBean(RagPublicationConfiguration.Prepared.class);
        }
    }

    @Bean
    public Invocation publicationInvocation(ReleaseProviderBudget budget) {
        if (!budget.configured()) throw new SecurityException("PUBLICATION_COMMAND_REQUIRES_PERSISTENT_HTTP_BUDGET");
        return new Invocation(true);
    }

    @Bean
    public KnowledgeCatalog knowledgeCatalog(ObjectMapper mapper, RagProperties properties, DataSource dataSource,
            ObjectProvider<RagPublicationConfiguration.Prepared> prepared) throws java.io.IOException {
        return new RagConfig().knowledgeCatalog(mapper, properties, dataSource, prepared);
    }

    @Bean(destroyMethod = "close")
    public CatalogLifecycle catalogLifecycle(KnowledgeCatalog catalog) { return catalog.lifecycle(); }

    @Bean(destroyMethod = "close")
    public RagKnowledgeService ragKnowledgeService(RagProperties properties, CatalogLifecycle lifecycle,
                                                   ReleaseProviderBudget budget) {
        return new RagConfig().ragKnowledgeService(properties, lifecycle, budget);
    }
}
