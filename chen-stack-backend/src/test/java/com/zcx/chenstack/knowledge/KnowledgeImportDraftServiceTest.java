package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import com.zcx.chenstack.service.AiKnowledgeManagementService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 候选草稿批量保存的编排测试：不写数据库、不调用 Provider。 */
class KnowledgeImportDraftServiceTest {
    private final KnowledgeDocService documents = mock(KnowledgeDocService.class);
    private final AiKnowledgeManagementService drafts = mock(AiKnowledgeManagementService.class);
    private final KnowledgeDocProperties properties = new KnowledgeDocProperties();

    @Test
    void savesEachSelectedCandidateThroughExistingDraftServiceWithStableOriginRef() {
        when(documents.importTask("imp-1")).thenReturn(task());
        when(documents.ensureSourceDocument("imp-1", 7)).thenReturn(new KnowledgeDocService.SourceDocument(11, 12));
        when(drafts.createDraftFromImport(anyString(), anyString(), anyString(), anyString(), any(),
                anyList(), anyString(), eq(11L), eq(12L)))
                .thenAnswer(invocation -> draft(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(6)));
        KnowledgeImportDraftService service = new KnowledgeImportDraftService(documents, drafts, properties);
        KnowledgeImportDraftService.Result result = service.save("imp-1", "batch-1", List.of(
                new KnowledgeImportDraftService.CandidateInput("aaaaaaaaaaaaaaaa", "标题 A", "正文 A",
                        List.of("ka"), null),
                new KnowledgeImportDraftService.CandidateInput("bbbbbbbbbbbbbbbb", "标题 B", "正文 B",
                        List.of("kb"), "/login")), 7);
        assertEquals(2, result.saved().size());
        assertTrue(result.failed().isEmpty());
        verify(drafts).createDraftFromImport(eq("imp-1"), eq("标题 A"), eq("正文 A"),
                eq("ai-aaaaaaaaaaaa"), isNull(), eq(List.of("ka")),
                eq("imp:imp-1:aaaaaaaaaaaaaaaa"), eq(11L), eq(12L));
        verify(drafts).createDraftFromImport(eq("imp-1"), eq("标题 B"), eq("正文 B"),
                eq("ai-bbbbbbbbbbbb"), eq("/login"), eq(List.of("kb")),
                eq("imp:imp-1:bbbbbbbbbbbbbbbb"), eq(11L), eq(12L));
    }

    @Test
    void duplicateCandidateIdsAreSavedOnlyOnce() {
        when(documents.importTask("imp-1")).thenReturn(task());
        when(documents.ensureSourceDocument("imp-1", 7)).thenReturn(new KnowledgeDocService.SourceDocument(11, 12));
        when(drafts.createDraftFromImport(anyString(), anyString(), anyString(), anyString(), any(),
                anyList(), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> draft(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(6)));
        KnowledgeImportDraftService service = new KnowledgeImportDraftService(documents, drafts, properties);
        KnowledgeImportDraftService.Result result = service.save("imp-1", "batch-1", List.of(
                new KnowledgeImportDraftService.CandidateInput("aaaaaaaaaaaaaaaa", "标题", "正文", List.of(), null),
                new KnowledgeImportDraftService.CandidateInput("aaaaaaaaaaaaaaaa", "标题2", "正文2", List.of(), null)), 7);
        assertEquals(1, result.saved().size());
        verify(drafts, times(1)).createDraftFromImport(anyString(), anyString(), anyString(), anyString(), any(),
                anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void oneInvalidCandidateDoesNotDiscardOtherSavedDrafts() {
        when(documents.importTask("imp-1")).thenReturn(task());
        when(documents.ensureSourceDocument("imp-1", 7)).thenReturn(new KnowledgeDocService.SourceDocument(11, 12));
        when(drafts.createDraftFromImport(anyString(), anyString(), anyString(), anyString(), any(),
                anyList(), anyString(), anyLong(), anyLong()))
                .thenThrow(KnowledgeDocException.bad("DRAFT_TITLE_REQUIRED", "知识标题不能为空"))
                .thenAnswer(invocation -> draft(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(6)));
        KnowledgeImportDraftService service = new KnowledgeImportDraftService(documents, drafts, properties);
        KnowledgeImportDraftService.Result result = service.save("imp-1", "batch-1", List.of(
                new KnowledgeImportDraftService.CandidateInput("aaaaaaaaaaaaaaaa", "标题", "正文", List.of(), null),
                new KnowledgeImportDraftService.CandidateInput("bbbbbbbbbbbbbbbb", "标题 B", "正文 B", List.of(), null)), 7);
        assertEquals(1, result.saved().size());
        assertEquals(1, result.failed().size());
        assertEquals("DRAFT_TITLE_REQUIRED", result.failed().getFirst().code());
    }

    @Test
    void rejectingEmptySelectionPreventsEmptyDraftCreation() {
        KnowledgeImportDraftService service = new KnowledgeImportDraftService(documents, drafts, properties);
        KnowledgeDocException error = assertThrows(KnowledgeDocException.class,
                () -> service.save("imp-1", "batch-1", List.of(), 7));
        assertEquals("DRAFT_SELECTION_REQUIRED", error.code());
        verifyNoInteractions(drafts);
    }

    private static KnowledgeDocStore.ImportRow task() {
        return new KnowledgeDocStore.ImportRow("imp-1", "f".repeat(64), "Redis.md", "text/markdown", 10,
                "a".repeat(64), "chen-knowledge-private", "kb/objects/aa/aa.md", false, "PARSED",
                "md-plain-v1", 10, "b".repeat(64), null, 11L, 12L, 7, null, null, Instant.now());
    }

    private static AiKnowledgeManagementService.Draft draft(String requestId, String title, String originRef) {
        return new AiKnowledgeManagementService.Draft("1", "help-managed-1", title, "正文", "ai-1",
                null, "未关联页面", List.of(), "DRAFT", "2026-11-10 20:00:00", "管理员", originRef);
    }
}
