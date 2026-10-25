package com.zcx.chenstack.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagBudgetConfigurationTest {
    @Test void importedDotenvKeyOverridesTheLegacyCumulativeLimitWithoutChangingEmbedding() throws Exception {
        for (String configured : new String[]{"", "100"}) {
            MockEnvironment environment = new MockEnvironment();
            if (!configured.isEmpty()) environment.setProperty("CHEN_RAG_RELEASE_MAX_CHAT_HTTP_ATTEMPTS", configured);
            new YamlPropertySourceLoader().load("rag", new ClassPathResource("application-rag.yaml"))
                    .forEach(environment.getPropertySources()::addLast);
            RagPublicationProperties properties = Binder.get(environment).bind("chen.rag.release", RagPublicationProperties.class).get();
            assertEquals(configured.isEmpty() ? 20 : 100, properties.getMaxChatHttpAttempts());
            assertEquals(64, properties.getMaxEmbeddingHttpAttempts());
        }
    }
}
