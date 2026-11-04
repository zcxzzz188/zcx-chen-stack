package com.zcx.chenstack.service;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.RagKnowledgeService;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews.Page;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 三档产品模式的选择、可用性与历史兼容。
 *
 * <p>普通对话不再是可选模式；历史 chat 配置仍必须能被读取和执行，审计也不能因此丢失。</p>
 */
class AiRuntimeConfigurationModeTest {

    private static Map<String, Object> row(String key, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("config_key", key);
        row.put("config_value", value);
        row.put("config_version", 3L);
        row.put("updated_by", 1);
        row.put("updated_at", "2026-11-04 07:42:57");
        return row;
    }

    /** 只模拟本服务真正使用的 SQL 形状，不引入第二套数据库实现。 */
    private static JdbcTemplate jdbc(Map<String, String> stored) {
        return mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if ("queryForList".equals(name)) {
                String sql = (String) args[0];
                List<Map<String, Object>> rows = new ArrayList<>();
                if (sql.contains("WHERE config_key IN")) {
                    for (int index = 1; index < args.length; index++) {
                        String key = (String) args[index];
                        if (stored.containsKey(key)) rows.add(row(key, stored.get(key)));
                    }
                    return rows;
                }
                if (sql.contains("WHERE config_key=?")) {
                    String key = (String) args[1];
                    return stored.containsKey(key) ? List.of(row(key, stored.get(key))) : List.of();
                }
                return List.of();
            }
            if ("query".equals(name)) return List.of();
            if ("update".equals(name)) return 1;
            if ("queryForObject".equals(name)) return "管理员";
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RagKnowledgeService> ragProvider(RagKnowledgeService service) {
        ObjectProvider<RagKnowledgeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WikiPublicationService> wikiProvider(WikiPublicationService service) {
        ObjectProvider<WikiPublicationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private static AiRuntimeConfigurationService service(Map<String, String> stored, RagKnowledgeService rag,
                                                         WikiPublicationService wiki, boolean ragEnabled) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(ragEnabled);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new AiRuntimeConfigurationService(jdbc(stored), manager, new AiKnowledgeProperties("rag"),
                ragProvider(rag), wikiProvider(wiki), properties, 100, "user-assistant");
    }

    private static RagKnowledgeService readyRag() {
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.diagnostics()).thenReturn(Map.of("ragAvailability", "CONFIGURED", "reviewedChunks", 210));
        com.zcx.chenstack.rag.KnowledgeCatalog catalog = mock(com.zcx.chenstack.rag.KnowledgeCatalog.class);
        when(catalog.sourceIds()).thenReturn(java.util.Set.of("a", "b", "c"));
        when(rag.reviewedCatalog()).thenReturn(catalog);
        return rag;
    }

    private static WikiPublicationService readyWiki() {
        WikiPublicationService wiki = mock(WikiPublicationService.class);
        when(wiki.list(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new Page<>(List.of(), 17, 1, 1, List.of()));
        return wiki;
    }

    @Test void ordinaryChatIsNoLongerASelectableProductMode() {
        AiRuntimeConfigurationService configuration = service(new LinkedHashMap<>(), readyRag(), readyWiki(), true);
        WikiProductException rejected = assertThrows(WikiProductException.class,
                () -> configuration.setMode("chat", 1));
        assertEquals(400, rejected.status());
        assertTrue(rejected.getMessage().contains("普通对话"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("智能混合"), rejected.getMessage());
    }

    @Test void unknownModesAreRejectedBeforeAnyWrite() {
        AiRuntimeConfigurationService configuration = service(new LinkedHashMap<>(), readyRag(), readyWiki(), true);
        for (String mode : List.of("auto", "hybrid2", "")) {
            assertEquals(400, assertThrows(WikiProductException.class,
                    () -> configuration.setMode(mode, 1)).status(), mode);
        }
    }

    @Test void modesListIsExactlyTheThreeProductModes() {
        AiRuntimeConfigurationService configuration = service(new LinkedHashMap<>(), readyRag(), readyWiki(), true);
        AiRuntimeConfigurationService.Status status = configuration.status();
        assertEquals(List.of("rag", "wiki", "hybrid"),
                status.modes().stream().map(AiRuntimeConfigurationService.ModeAvailability::mode).toList());
        assertTrue(status.modes().stream().noneMatch(item -> "chat".equals(item.mode())));
        assertTrue(status.modes().stream().allMatch(AiRuntimeConfigurationService.ModeAvailability::available));
        assertEquals("rag", status.effectiveMode());
        assertEquals(100, status.dailyLimit());
        assertEquals(0, status.configVersion());
        assertEquals("ENV_DEFAULT", status.source());
    }

    @Test void legacyChatConfigurationKeepsWorkingWithoutBecomingSelectable() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("user-assistant.knowledge-mode", "chat");
        stored.put("quota.daily-limit", "100");
        AiRuntimeConfigurationService.Status status =
                service(stored, readyRag(), readyWiki(), true).status();
        assertEquals("chat", status.effectiveMode());
        assertEquals("chat", status.desiredMode());
        assertTrue(status.applied());
        assertNull(status.applyError());
        assertEquals(List.of("rag", "wiki", "hybrid"),
                status.modes().stream().map(AiRuntimeConfigurationService.ModeAvailability::mode).toList());
        assertEquals("DATABASE", status.source());
    }

    @Test void hybridFallsBackToTheOnlyUsableSideAndSaysSo() {
        AiRuntimeConfigurationService.Status ragDown =
                service(new LinkedHashMap<>(), null, readyWiki(), true).status();
        assertFalse(ragDown.modes().get(0).available());
        assertTrue(ragDown.modes().get(2).available());
        assertTrue(ragDown.modes().get(2).note().contains("只会使用 Wiki"), ragDown.modes().get(2).note());

        AiRuntimeConfigurationService.Status wikiDown =
                service(new LinkedHashMap<>(), readyRag(), null, true).status();
        assertTrue(wikiDown.modes().get(2).available());
        assertTrue(wikiDown.modes().get(2).note().contains("只会使用 RAG"), wikiDown.modes().get(2).note());
    }

    @Test void hybridCannotBeSelectedWhenNeitherKnowledgeSourceIsReady() {
        AiRuntimeConfigurationService configuration = service(new LinkedHashMap<>(), null, null, true);
        assertFalse(configuration.status().modes().get(2).available());
        assertEquals(409, assertThrows(WikiProductException.class,
                () -> configuration.setMode("hybrid", 1)).status());
    }

    @Test void knowledgeSummaryComesFromTheLiveServicesInsteadOfFixedNumbers() {
        Map<String, Object> summary = service(new LinkedHashMap<>(), readyRag(), readyWiki(), true)
                .status().knowledge();
        assertEquals(true, summary.get("ragReady"));
        assertEquals(3, summary.get("ragSources"));
        assertEquals(210, summary.get("reviewedChunks"));
        assertEquals(true, summary.get("wikiReady"));
        assertEquals(17L, summary.get("wikiPages"));
    }

    @Test void disablingRagMakesItAndHybridUnavailableButKeepsWiki() {
        AiRuntimeConfigurationService.Status status =
                service(new LinkedHashMap<>(), readyRag(), readyWiki(), false).status();
        assertFalse(status.modes().get(0).available());
        assertTrue(status.modes().get(1).available());
        assertTrue(status.modes().get(2).available());
        assertEquals(409, assertThrows(WikiProductException.class,
                () -> service(new LinkedHashMap<>(), readyRag(), readyWiki(), false).setMode("rag", 1)).status());
    }
}
