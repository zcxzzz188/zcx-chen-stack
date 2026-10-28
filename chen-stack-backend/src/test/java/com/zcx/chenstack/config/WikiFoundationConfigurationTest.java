package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.wiki.WikiFoundation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WikiFoundationConfigurationTest {
    @Test void defaultDisabledRequiresNoDatabaseOrG2Beans() {
        new ApplicationContextRunner().withUserConfiguration(WikiFoundationConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("wikiFoundation"));
                });
    }

    @Test void optInOnlyConstructsFoundationWithoutSchemaOrProviderSideEffects() {
        DataSource dataSource = mock(DataSource.class);
        CatalogLifecycle catalog = mock(CatalogLifecycle.class);
        new ApplicationContextRunner().withUserConfiguration(WikiFoundationConfiguration.class)
                .withPropertyValues("chen.wiki.enabled=true")
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(CatalogLifecycle.class, () -> catalog)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AdminSecurity.class, AdminSecurity::new)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(WikiFoundation.class));
                    verifyNoInteractions(dataSource, catalog);
                });
    }
}

