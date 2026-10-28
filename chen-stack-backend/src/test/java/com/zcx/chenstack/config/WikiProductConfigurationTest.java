package com.zcx.chenstack.config;

import com.zcx.chenstack.wiki.WikiManagementService;
import com.zcx.chenstack.wiki.WikiPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

class WikiProductConfigurationTest {
    @Test void productHasNoDatabaseOrModelSideEffectsUnlessBothFlagsAreEnabled() {
        for (String[] properties : new String[][] { {}, {"chen.wiki.enabled=true"}, {"chen.wiki.product.enabled=true"} }) {
            new ApplicationContextRunner().withUserConfiguration(WikiProductConfiguration.class).withPropertyValues(properties)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(context.containsBean("wikiPublicationService"));
                        assertTrue(context.getBeansOfType(WikiPublicationService.class).isEmpty());
                        assertTrue(context.getBeansOfType(WikiManagementService.class).isEmpty());
                    });
        }
    }
}
