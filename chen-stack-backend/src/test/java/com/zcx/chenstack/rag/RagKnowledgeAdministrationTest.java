package com.zcx.chenstack.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagKnowledgeAdministrationTest {
    private final KnowledgeDocument old = document("help-login", "v1", "PLATFORM_HELP", "/login", "PUBLIC", "DOCUMENT_REVIEWED");

    @Test void updatePreservesSourceAuthorityAndRequiresNewVersion() {
        assertDoesNotThrow(() -> RagKnowledgeAdministration.validateReplacement(old,
                document("help-login", "v2", "PLATFORM_HELP", "/login", "PUBLIC", "DOCUMENT_REVIEWED")));
        for (KnowledgeDocument invalid : List.of(
                document("help-password-reset", "v2", "PLATFORM_HELP", "/login", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-login", "v1", "PLATFORM_HELP", "/login", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-login", "v2", "ARTICLE", "/login", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-login", "v2", "PLATFORM_HELP", "/reset", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-login", "v2", "PLATFORM_HELP", "/login", "PRIVATE", "DOCUMENT_REVIEWED"),
                document("help-login", "v2", "PLATFORM_HELP", "/login", "PUBLIC", "REVOKED"))) {
            assertThrows(IllegalArgumentException.class, () -> RagKnowledgeAdministration.validateReplacement(old, invalid));
        }
    }

    @Test void additionOnlyAcceptsManagedPublicReviewedHelpWithKnownPage() {
        assertDoesNotThrow(() -> RagKnowledgeAdministration.validateAddition(
                document("help-managed-abc123", "manual-1", "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED")));
        for (KnowledgeDocument invalid : List.of(
                document("help-login", "manual-1", "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-managed-abc123", "manual-1", "ARTICLE", "/editor", "PUBLIC", "DOCUMENT_REVIEWED"),
                document("help-managed-abc123", "manual-1", "PLATFORM_HELP", "/editor", "PRIVATE", "DOCUMENT_REVIEWED"),
                document("help-managed-abc123", "manual-1", "PLATFORM_HELP", "/editor", "PUBLIC", "REVOKED"),
                document("help-managed-abc123", "manual-1", "PLATFORM_HELP", "/unknown", "PUBLIC", "DOCUMENT_REVIEWED"),
                new KnowledgeDocument("help-managed-abc123", "Login help", "Verified help content", "manual-1",
                        "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", null, List.of("login")))) {
            assertThrows(IllegalArgumentException.class, () -> RagKnowledgeAdministration.validateAddition(invalid));
        }
    }

    private static KnowledgeDocument document(String id, String version, String type, String url, String visibility, String review) {
        return new KnowledgeDocument(id, "Login help", "Verified help content", version, type, url, visibility, review, Instant.EPOCH, List.of("login"));
    }
}
