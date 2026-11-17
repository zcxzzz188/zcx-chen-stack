package com.zcx.chenstack.service;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.RagKnowledgeService;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置读写的 SQL 形状回归。
 *
 * <p>G4 把 {@code ai_runtime_config_event} 合并进 {@code knowledge_audit_log} 时，两条语句各残留了
 * 一段旧子句：审计插入多出一条 {@code VALUES(?,?,?,?,?)}，最近修改查询多出一个 {@code WHERE}。
 * 前者让保存直接抛 {@code BadSqlGrammarException}（接口 503「配置服务暂不可用」），
 * 后者被 catch 静默吞掉让"最近修改"永远为空。</p>
 *
 * <p>Mockito 不会解析 SQL，所以旧测试无法发现这类错误；这里直接锁死语句形状。</p>
 */
class AiRuntimeConfigurationSqlTest {
    private final List<String> executed = new ArrayList<>();

    private JdbcTemplate recordingJdbc() {
        Map<String, String> stored = new LinkedHashMap<>();
        return mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if (args.length > 0 && args[0] instanceof String sql) executed.add(sql);
            return switch (name) {
                case "queryForList" -> List.of();
                case "query" -> List.of();
                case "update" -> 1;
                case "queryForObject" -> "管理员";
                default -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
            };
        });
    }

    private static ObjectProvider<RagKnowledgeService> ragProvider() {
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.diagnostics()).thenReturn(Map.of("ragAvailability", "CONFIGURED", "reviewedChunks", 210));
        KnowledgeCatalog catalog = mock(KnowledgeCatalog.class);
        when(catalog.sourceIds()).thenReturn(Set.of("a", "b", "c"));
        when(rag.reviewedCatalog()).thenReturn(catalog);
        @SuppressWarnings("unchecked") ObjectProvider<RagKnowledgeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rag);
        return provider;
    }

    private static ObjectProvider<WikiPublicationService> wikiProvider() {
        WikiPublicationService wiki = mock(WikiPublicationService.class);
        when(wiki.list(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(new Page<>(List.of(), 17, 1, 1, List.of()));
        @SuppressWarnings("unchecked") ObjectProvider<WikiPublicationService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wiki);
        return provider;
    }

    private AiRuntimeConfigurationService service() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new AiRuntimeConfigurationService(recordingJdbc(), manager, new AiKnowledgeProperties("rag"),
                ragProvider(), wikiProvider(), properties, 100, "user-assistant");
    }

    private static int count(String text, String token) {
        int hits = 0;
        int from = 0;
        String upper = text.toUpperCase(Locale.ROOT);
        while ((from = upper.indexOf(token, from)) >= 0) { hits++; from += token.length(); }
        return hits;
    }

    @Test
    void modeSaveWritesExactlyOneValuesClauseIntoTheUnifiedAuditTable() {
        AiRuntimeConfigurationService configuration = service();

        // 这里的 JDBC 桩不持久化，保存后的回读必然仍是默认模式，因此允许 409；
        // 本用例只关心"保存语句能否被 MySQL 接受"。
        assertThrows(com.zcx.chenstack.wiki.WikiProductException.class,
                () -> configuration.setMode("hybrid", 1));

        String audit = executed.stream()
                .filter(sql -> sql.contains("INSERT INTO knowledge_audit_log"))
                .findFirst().orElseThrow(() -> new AssertionError("保存必须写一条统一的配置审计"));
        assertEquals(1, count(audit, "VALUES"), "审计插入只能有一条 VALUES 子句: " + audit);
        assertTrue(audit.contains("'CONFIG_CHANGE'"), audit);
        assertTrue(audit.contains("'CONFIG'"), audit);
    }

    @Test
    void recentChangesQueryUsesExactlyOneWhereClause() {
        AiRuntimeConfigurationService configuration = service();

        configuration.status();

        String recent = executed.stream()
                .filter(sql -> sql.contains("FROM knowledge_audit_log") && sql.contains("config_key IN"))
                .findFirst().orElseThrow(() -> new AssertionError("状态必须读取最近的配置变更"));
        assertEquals(1, count(recent, "WHERE"), "最近修改查询只能有一个 WHERE: " + recent);
        assertTrue(recent.contains("target_kind='CONFIG'"), recent);
    }
}
