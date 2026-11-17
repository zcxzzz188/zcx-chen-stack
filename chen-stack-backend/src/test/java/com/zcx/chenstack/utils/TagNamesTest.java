package com.zcx.chenstack.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TagNamesTest {

    @Test
    void normalizesOnlySpringBootAliases() {
        for (String alias : new String[]{"springboot", "spring boot", "SpringBoot", "Spring Boot"}) {
            assertEquals("spring boot", TagNames.normalize(alias));
        }
        assertEquals("spring", TagNames.normalize("spring"));
        assertEquals("spring cloud", TagNames.normalize("spring cloud"));
    }

    @Test
    void preservesOtherArticleTagsAndKeepsOneSpringBootTag() {
        assertEquals("spring boot,缓存, spring,Spring Cloud,redis",
                TagNames.normalizeArticleTags("SpringBoot,缓存, spring,Spring Cloud,spring boot,redis"));
        assertEquals("", TagNames.normalizeArticleTags(""));
        assertNull(TagNames.normalizeArticleTags(null));
    }
}
