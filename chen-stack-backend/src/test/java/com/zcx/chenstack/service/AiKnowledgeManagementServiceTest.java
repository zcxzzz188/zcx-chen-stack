package com.zcx.chenstack.service;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.rag.*;
import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiKnowledgeManagementServiceTest {
    private final KnowledgeDocument source = new KnowledgeDocument("password_reset", "忘记密码", "使用已绑定邮箱验证后重设密码；没有有效绑定邮箱验证码时不能继续。",
            "v1", "PLATFORM_HELP", "/reset", "PUBLIC", "DOCUMENT_REVIEWED", Instant.parse("2026-10-30T00:00:00Z"), List.of("忘记密码"));
    private final KnowledgeCatalog catalog = new KnowledgeCatalog(List.of(source));
    private final RagKnowledgeService rag = mock(RagKnowledgeService.class);
    private final AdminSecurity security = mock(AdminSecurity.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<WikiPublicationService> wiki = mock(ObjectProvider.class);
    private AiKnowledgeManagementService service() {
        return new AiKnowledgeManagementService(catalog.lifecycle(), rag, new AiKnowledgeProperties("rag"), security, wiki);
    }

    @Test void ordinaryUserCannotReadStatusOrKnowledge() {
        assertEquals(403, assertThrows(WikiProductException.class, () -> service().status()).status());
        assertEquals(403, assertThrows(WikiProductException.class, () -> service().documents("", 1, 12)).status());
        assertEquals(403, assertThrows(WikiProductException.class, () -> service().test("忘记密码", "rag")).status());
        verifyNoInteractions(rag);
    }

    @Test void documentsRetainEvidenceAndExpectedGenerationWithoutTouchingIndex() {
        when(security.hasKnowledgePermission("ai:rag:read")).thenReturn(true);
        var result = service().documents("忘记", 1, 12);
        assertEquals(1, result.total());
        assertEquals(source.text(), result.items().getFirst().text());
        assertEquals(KnowledgeCatalog.hash(KnowledgeCatalog.normalize(source.text())), result.items().getFirst().contentHash());
        assertEquals(catalog.epoch(), result.items().getFirst().generation());
        assertTrue(result.items().getFirst().effective());
        verifyNoInteractions(rag);
    }

    @Test void retrievalRunsTheVerifiedBusinessRetrieverAndReturnsMatchedText() {
        when(security.hasKnowledgePermission("ai:knowledge:test")).thenReturn(true);
        when(rag.retrieve("忘记密码")).thenReturn(new KnowledgeEvidence(catalog.chunks(), catalog));
        var result = service().test("忘记密码", "rag");
        assertEquals("rag", result.mode());
        assertEquals(1, result.total());
        assertEquals(source.text(), result.items().getFirst().text());
        assertEquals("/reset", result.items().getFirst().sourceUrl());
        verify(rag).retrieve("忘记密码");
    }

    @Test void retrievalDoesNotPresentUnmatchedDocumentTextAsAnActualHit() {
        when(security.hasKnowledgePermission("ai:knowledge:test")).thenReturn(true);
        KnowledgeDocument lengthy = new KnowledgeDocument(source.sourceId(), source.title(), "A".repeat(420) + "未命中的末尾说明",
                source.version(), source.sourceType(), source.sourceUrl(), source.visibility(), source.reviewStatus(), source.updatedAt(), source.keywords());
        KnowledgeCatalog complete = new KnowledgeCatalog(List.of(lengthy));
        var matched = complete.chunks().getFirst();
        when(rag.retrieve("忘记密码")).thenReturn(new KnowledgeEvidence(List.of(matched), complete));
        var management = new AiKnowledgeManagementService(complete.lifecycle(), rag, new AiKnowledgeProperties("rag"), security, wiki);
        var result = management.test("忘记密码", "rag");
        assertEquals(matched.getText(), result.items().getFirst().text());
        assertFalse(result.items().getFirst().text().contains("未命中的末尾说明"));
    }

    @Test void invalidModesAndUnavailableWikiAreNotPretendedSupportedOnline() {
        when(security.hasKnowledgePermission("ai:knowledge:test")).thenReturn(true);
        assertEquals(400, assertThrows(WikiProductException.class, () -> service().test("忘记密码", "hybrid")).status());
        assertEquals(503, assertThrows(WikiProductException.class, () -> service().test("忘记密码", "wiki")).status());
        verifyNoInteractions(rag);
    }

    @Test void statusReportsConfiguredModeAndActualCapabilities() {
        when(security.hasKnowledgePermission("ai:rag:read")).thenReturn(true);
        when(rag.diagnostics()).thenReturn(Map.of("ragAvailability", "RECOVERY_REQUIRED"));
        var result = service().status();
        assertEquals("rag", result.mode());
        assertEquals("RECOVERY_REQUIRED", result.rag().get("ragAvailability"));
        assertTrue(result.capabilities().get("ragRead"));
        assertFalse(result.capabilities().get("ragManage"));
    }
}
