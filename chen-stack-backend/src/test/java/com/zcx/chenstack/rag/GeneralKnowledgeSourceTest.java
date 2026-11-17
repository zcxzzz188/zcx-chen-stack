package com.zcx.chenstack.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 通用技术知识沿用 G3 已有的平台首页锚点，不扩大页面白名单。 */
class GeneralKnowledgeSourceTest {
    @Test
    void generalKnowledgeUsesExistingPlatformAnchorAndStillPassesWhitelist() {
        assertTrue(KnowledgeCatalog.validSourceUrl(KnowledgeCatalog.GENERAL_SOURCE_URL));
        assertFalse(KnowledgeCatalog.validSourceUrl(null));
        assertFalse(KnowledgeCatalog.validSourceUrl(""));
        assertFalse(KnowledgeCatalog.validSourceUrl("/not-a-real-page"));
        assertFalse(KnowledgeCatalog.validSourceUrl("https://evil.example/x"));
    }

    @Test
    void generalKnowledgeIsIndexedWithoutSpecificUserPage() {
        KnowledgeDocument general = new KnowledgeDocument("help-general", "Redis 通用技术知识",
                "Redis 采用惰性删除与定期删除两种过期键删除策略。", "v1", "PLATFORM_HELP",
                KnowledgeCatalog.GENERAL_SOURCE_URL, "PUBLIC", "DOCUMENT_REVIEWED", Instant.now(),
                List.of("Redis", "过期键"));
        KnowledgeCatalog catalog = new KnowledgeCatalog(List.of(general));
        assertEquals(KnowledgeCatalog.GENERAL_SOURCE_URL, catalog.source("help-general").sourceUrl());
        assertFalse(catalog.chunks().isEmpty());
        assertEquals(KnowledgeCatalog.GENERAL_SOURCE_URL, catalog.chunks().getFirst().getMetadata().get("source_url"));
    }
}
