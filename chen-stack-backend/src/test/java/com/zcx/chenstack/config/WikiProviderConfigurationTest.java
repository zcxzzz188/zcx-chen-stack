package com.zcx.chenstack.config;

import com.zcx.chenstack.wiki.WikiModel;
import com.zcx.chenstack.rag.NormalAiUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class WikiProviderConfigurationTest {
    @TempDir java.nio.file.Path temporary;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WikiProviderConfiguration.class);

    @Test void disabledAndDefaultProviderDoNotCreateModel() {
        for (String[] properties : new String[][]{{}, {"chen.wiki.enabled=true"},
                {"chen.wiki.enabled=true", "chen.wiki.compiler.enabled=true"}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("deepseekWikiModel"));
                assertFalse(context.containsBean("mockWikiModel"));
            });
        }
    }

    @Test void mockNeedsNumericLoopbackAndNoSharedProviderCredentials() {
        enabled().withPropertyValues("chen.wiki.compiler.provider=mock", "chen.wiki.compiler.mock-base-url=http://127.0.0.1:1/v1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(WikiModel.class).external());
                });
        enabled().withPropertyValues("chen.wiki.compiler.provider=mock", "chen.wiki.compiler.mock-base-url=https://api.deepseek.com/v1")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void deepseekConstructionNeverSendsAndMissingGrantDeniesUse() {
        enabled().withPropertyValues("chen.wiki.compiler.provider=deepseek", "spring.ai.openai.base-url=https://api.deepseek.com",
                "spring.ai.openai.api-key=synthetic", "spring.ai.openai.chat.options.model=deepseek-flash")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WikiModel model = context.getBean(WikiModel.class);
                    assertTrue(model.external());
                    assertThrows(SecurityException.class, () -> model.authorize("not-approved", "a".repeat(64)));
                    assertThrows(SecurityException.class, () -> model.generate("system", "data", 2048));
                });
        enabled().withPropertyValues("chen.wiki.compiler.provider=unexpected")
                .run(context -> assertNotNull(context.getStartupFailure()));
        enabled().withPropertyValues("chen.wiki.compiler.provider=deepseek", "spring.ai.openai.base-url=https://example.com",
                "spring.ai.openai.api-key=synthetic", "spring.ai.openai.chat.options.model=deepseek-flash")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test void explicitNormalUsageUsesDurableMeterWithoutTemporaryGrant() {
        enabled().withBean(NormalAiUsage.class,
                () -> new NormalAiUsage(true, temporary.resolve("normal-compiler.jsonl").toString()))
                .withPropertyValues("chen.wiki.compiler.provider=deepseek", "spring.ai.openai.base-url=https://api.deepseek.com",
                        "spring.ai.openai.api-key=synthetic", "spring.ai.openai.chat.options.model=deepseek-flash")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WikiModel model = context.getBean(WikiModel.class);
                    assertTrue(model.external());
                    assertDoesNotThrow(() -> model.authorize("normal-admin-task", null));
                    assertDoesNotThrow(() -> model.authorize("normal-admin-task", "a".repeat(64)));
                    assertThrows(SecurityException.class, () -> model.generate("system", "data", 2048));
                });
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("chen.wiki.enabled=true", "chen.wiki.compiler.enabled=true");
    }
}
