package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 数据权威回归：AI 候选知识快照与知识目录版本号必须落在正确的表上。
 *
 * <p>历史缺陷一：候选快照是"刷新后唯一可恢复状态"，却写在 {@code knowledge_audit_log.detail_json}。
 * 审计不可变且可归档，不能承担业务状态。此处锁定：读写都走 {@code knowledge_import_task}。</p>
 *
 * <p>历史缺陷二：知识目录版本号直接 {@code UPDATE rag_catalog_state SET generation=generation+1}，
 * 与 G3 受控发布账本形成双写，会使 {@code snapshot_sha256} 失真并在下次启动失败关闭。
 * 此处锁定：知识目录版本号是纯推导读，不写任何表。</p>
 */
class KnowledgeAnalysisSnapshotAuthorityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final KnowledgeDocStore store = new KnowledgeDocStore(jdbc, new ObjectMapper());

    @Test
    void analysisSnapshotIsWrittenToImportTaskOnly() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.recordAnalysis("imp-1", "COMPLETED", "deepseek-flash", "{\"candidates\":[]}", null, null);

        String sql = captureUpdate();
        assertTrue(sql.contains("UPDATE knowledge_import_task"), sql);
        assertTrue(sql.contains("analysis_status=?"), sql);
        assertTrue(sql.contains("analysis_result_json=?"), sql);
        assertTrue(sql.contains("analysis_attempts=analysis_attempts+1"), sql);
        assertFalse(sql.contains("knowledge_audit_log"), sql);
    }

    @Test
    void failedAnalysisRecordsErrorColumnsOnImportTask() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        store.recordAnalysis("imp-1", "FAILED", "deepseek-flash", null, "AI_ANALYSIS_TIMEOUT", "模型超时");

        String sql = captureUpdate();
        assertTrue(sql.contains("knowledge_import_task"), sql);
        assertTrue(sql.contains("analysis_error_code=?"), sql);
        assertTrue(sql.contains("analysis_error_message=?"), sql);
        assertFalse(sql.contains("knowledge_audit_log"), sql);
    }

    @Test
    void analysisSnapshotOnMissingTaskIsReportedNotSilentlyIgnored() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThrows(KnowledgeDocException.class,
                () -> store.recordAnalysis("imp-missing", "FAILED", "m", null, "AI_ANALYSIS_FAILED", "x"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analysisSnapshotIsReadFromImportTaskOnly() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertTrue(store.latestImportAnalysis("imp-1").isEmpty());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getValue().contains("FROM knowledge_import_task"), sql.getValue());
        assertTrue(sql.getValue().contains("analysis_result_json"), sql.getValue());
        assertFalse(sql.getValue().contains("knowledge_audit_log"), sql.getValue());
    }

    @Test
    void directoryRevisionIsDerivedAndNeverWritesTheReleaseLedger() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);

        assertEquals(42L, store.revision());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class));
        assertTrue(sql.getValue().contains("knowledge_document_version"), sql.getValue());
        assertTrue(sql.getValue().contains("mirror_mode='AUTHORITATIVE'"), sql.getValue());
        assertFalse(sql.getValue().contains("rag_catalog_state"), sql.getValue());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    private String captureUpdate() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    /**
     * 读取路径：刷新/重进弹窗恢复候选时，数据必须来自导入任务快照，
     * 而不是"最近一条 AI_ANALYSIS_* 审计"。
     */
    @Test
    @SuppressWarnings("unchecked")
    void latestAnalysisIsRestoredFromImportTaskSnapshot() throws Exception {
        String snapshot = "{\"title\":\"Redis 键过期\",\"sourceDocumentId\":65,\"sourceVersionId\":72,"
                + "\"reviewStatus\":\"REVIEW_PENDING\",\"inputTruncated\":false,\"candidatesTruncated\":false,"
                + "\"warning\":\"\",\"candidates\":[{\"candidateId\":\"c1\",\"title\":\"Redis 键过期\"}]}";
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
            when(rs.getString(1)).thenReturn("COMPLETED");
            when(rs.getString(2)).thenReturn("deepseek-flash");
            when(rs.getString(3)).thenReturn(snapshot);
            when(rs.getString(4)).thenReturn(null);
            when(rs.getString(5)).thenReturn(null);
            when(rs.getInt(6)).thenReturn(1);
            when(rs.getTimestamp(7)).thenReturn(java.sql.Timestamp.from(java.time.Instant.parse("2026-11-11T03:00:00Z")));
            return List.of(mapper.mapRow(rs, 0));
        });

        KnowledgeDocService documents = mock(KnowledgeDocService.class);
        when(documents.importTask("imp-1")).thenReturn(new KnowledgeDocStore.ImportRow("imp-1", "f".repeat(64),
                "Redis.md", "text/markdown", 100L, "a".repeat(64), "bucket", "kb/objects/x", false, "PARSED",
                "parser-v1", 936, "b".repeat(64), null, 65L, 72L, 1, null, null,
                java.time.Instant.parse("2026-11-11T02:00:00Z")));
        when(documents.store()).thenReturn(store);
        KnowledgeAnalysisService service = new KnowledgeAnalysisService(documents, mock(KnowledgeCandidateExtractor.class));

        KnowledgeAnalysisService.StoredAnalysis restored = service.latest("imp-1");

        assertEquals("COMPLETED", restored.status());
        assertEquals("Redis 键过期", restored.title());
        assertEquals("deepseek-flash", restored.model());
        assertEquals(1, restored.candidates().size());
        assertEquals("Redis 键过期", restored.candidates().getFirst().get("title"));
        assertEquals(65L, restored.sourceDocumentId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notAnalyzedImportTaskDoesNotPretendToHaveCandidates() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        KnowledgeDocService documents = mock(KnowledgeDocService.class);
        when(documents.importTask("imp-2")).thenReturn(new KnowledgeDocStore.ImportRow("imp-2", "f".repeat(64),
                "a.txt", "text/plain", 10L, "a".repeat(64), "bucket", "kb/objects/y", false, "PARSED",
                null, null, null, null, null, null, 1, null, null, null));
        when(documents.store()).thenReturn(store);
        KnowledgeAnalysisService service = new KnowledgeAnalysisService(documents, mock(KnowledgeCandidateExtractor.class));

        KnowledgeAnalysisService.StoredAnalysis analysis = service.latest("imp-2");

        assertEquals("NOT_ANALYZED", analysis.status());
        assertTrue(analysis.candidates().isEmpty());
        assertNull(analysis.model());
    }
}
