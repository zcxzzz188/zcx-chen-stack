package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.wiki.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WikiCompilationConfigurationTest {
    @Test void defaultsAndFoundationOnlyDoNotCreateCompilerOrRequireProvider() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(WikiCompilationConfiguration.class);
        for (String[] properties : new String[][]{{}, {"chen.wiki.enabled=true"}, {"chen.wiki.compiler.enabled=true"}}) {
            runner.withPropertyValues(properties).run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("wikiCompilationService"));
            });
        }
    }

    @Test void explicitModelIsRequiredAndConstructionDoesNotSendOrAccessDatabase() {
        DataSource dataSource = mock(DataSource.class);
        WikiFoundation foundation = mock(WikiFoundation.class);
        WikiModel model = mock(WikiModel.class); when(model.id()).thenReturn("wiki-mock");
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(WikiCompilationConfiguration.class)
                .withPropertyValues("chen.wiki.enabled=true", "chen.wiki.compiler.enabled=true")
                .withBean(DataSource.class, () -> dataSource).withBean(WikiFoundation.class, () -> foundation)
                .withBean(ObjectMapper.class, ObjectMapper::new);
        runner.run(context -> assertNotNull(context.getStartupFailure()));
        runner.withBean(WikiModel.class, () -> model).run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(WikiCompilationService.class));
            verifyNoInteractions(dataSource, foundation);
            verify(model, never()).generate(anyString(), anyString(), anyInt());
        });
    }
}
