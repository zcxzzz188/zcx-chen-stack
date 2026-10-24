package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicationAuthorizationTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final byte[] MANIFEST = manifest(PublicationManifest.Operation.PUBLISH, "isolated");

    private static byte[] manifest(PublicationManifest.Operation operation, String environment) {
        try {
            return JSON.writeValueAsBytes(new PublicationManifest(1, "7".repeat(40), "a".repeat(64), environment,
                    "chen_synthetic_release", "e".repeat(64), Map.of(
                    PublicationManifest.PLATFORM_HELP_RESOURCE, "b".repeat(64),
                    PublicationManifest.CAPABILITIES_RESOURCE, "c".repeat(64)), 4, "d".repeat(64), operation,
                    operation == PublicationManifest.Operation.REVOKE ? "help-login" : null, false, false, List.of()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PublicationAuthorization.Grant grant() {
        return new PublicationAuthorization.Grant(1, "synthetic-operation", "executor-b", "isolated",
                PublicationManifest.sha256(MANIFEST), 3, PublicationManifest.Operation.PUBLISH, true);
    }

    private PublicationAuthorization.Grant verify(byte[] operation, byte[] manifest) {
        return PublicationAuthorization.verify(operation, manifest, "executor-b", "isolated", PublicationManifest.Operation.PUBLISH);
    }

    private byte[] changedGrant(String field, Object value) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(grant());
        node.set(field, JSON.valueToTree(value));
        return JSON.writeValueAsBytes(node);
    }

    @Test void acceptsConfirmedLocalOperatorRecordAndReturnsBoundBudget() throws Exception {
        assertEquals(grant(), verify(JSON.writeValueAsBytes(grant()), MANIFEST));
    }

    @Test void deniesMissingOperationOrTrustedRuntimeIdentity() throws Exception {
        assertThrows(SecurityException.class, () -> verify(null, MANIFEST));
        byte[] operation = JSON.writeValueAsBytes(grant());
        assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(operation, MANIFEST, null,
                "isolated", PublicationManifest.Operation.PUBLISH));
        assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(operation, MANIFEST, "executor-b",
                null, PublicationManifest.Operation.PUBLISH));
        assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(operation, MANIFEST, "executor-b", "isolated", null));
    }

    @Test void rejectsTamperedScopeAndLegacySignatureEnvelopes() throws Exception {
        for (String field : List.of("manifestSha256", "operator", "environment", "allowedOperation")) {
            Object changed = switch (field) {
                case "manifestSha256" -> "f".repeat(64);
                case "allowedOperation" -> "RECOVER";
                default -> "other";
            };
            assertThrows(SecurityException.class, () -> verify(changedGrant(field, changed), MANIFEST));
        }
        assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(Map.of(
                "payload", "e30", "signature", "legacy-signature")), MANIFEST));
    }

    @Test void rejectsUnconfirmedWrongOperatorEnvironmentAndOperation() throws Exception {
        for (var field : Map.<String, Object>of("confirmed", false, "operator", "other-executor", "environment", "production",
                "allowedOperation", "RECOVER").entrySet()) {
            assertThrows(SecurityException.class, () -> verify(changedGrant(field.getKey(), field.getValue()), MANIFEST));
        }
    }

    @Test void rejectsUnknownSchemaMissingConfirmationAndLegacyExpiryFields() throws Exception {
        for (int schema : List.of(0, 2)) {
            assertThrows(SecurityException.class, () -> verify(changedGrant("schemaVersion", schema), MANIFEST));
        }
        assertThrows(SecurityException.class, () -> verify(changedGrant("schemaVersion", null), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("confirmed", null), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("confirmed", "true"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("schemaVersion", 1.5), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("maxEmbeddingRequests", "3"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("expiresAt", "2099-01-01T00:00:00Z"), MANIFEST));
    }

    @Test void rejectsNegativeBudgetMissingIdentityAndUnknownFields() throws Exception {
        assertThrows(SecurityException.class, () -> verify(changedGrant("maxEmbeddingRequests", -1), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("operationId", " "), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("operationId", "a".repeat(81)), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("operationId", "操作-1"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("operator", "operator\nsecret"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("unexpected", "not-allowed"), MANIFEST));
    }

    @Test void bindsOperationToExactRawManifestBytes() throws Exception {
        byte[] operation = JSON.writeValueAsBytes(grant());
        byte[] changed = (new String(MANIFEST, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class, () -> verify(operation, changed));
        assertThrows(SecurityException.class, () -> verify(changedGrant("manifestSha256", "a".repeat(64)), MANIFEST));
    }

    @Test void operationAndEnvironmentMustAlsoMatchTheManifest() throws Exception {
        for (byte[] bytes : List.of(manifest(PublicationManifest.Operation.REVOKE, "isolated"),
                manifest(PublicationManifest.Operation.PUBLISH, "production"))) {
            assertThrows(SecurityException.class, () -> verify(changedGrant("manifestSha256", PublicationManifest.sha256(bytes)), bytes));
        }
    }

    @Test void deniesMalformedDuplicateTrailingOrOversizedOperationRecords() throws Exception {
        for (String json : List.of("{}", "{\"payload\":\"not+base64\",\"signature\":\"a\"}",
                "{\"schemaVersion\":1,\"schemaVersion\":1}", "null", "{} {}")) {
            assertThrows(SecurityException.class, () -> verify(json.getBytes(StandardCharsets.UTF_8), MANIFEST));
        }
        assertThrows(SecurityException.class, () -> verify(new byte[16_385], MANIFEST));
        assertThrows(SecurityException.class, () -> verify("{}".getBytes(StandardCharsets.UTF_16), MANIFEST));
        byte[] valid = JSON.writeValueAsBytes(grant());
        assertThrows(SecurityException.class, () -> verify((new String(valid, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8), MANIFEST));
    }

    @Test void revokeOperationRequiresZeroEmbeddingBudget() throws Exception {
        byte[] manifest = manifest(PublicationManifest.Operation.REVOKE, "isolated");
        for (int budget : List.of(0, 1)) {
            var grant = new PublicationAuthorization.Grant(1, "revoke-operation", "executor-b", "isolated",
                    PublicationManifest.sha256(manifest), budget, PublicationManifest.Operation.REVOKE, true);
            byte[] operation = JSON.writeValueAsBytes(grant);
            if (budget == 0) {
                assertEquals(grant, PublicationAuthorization.verify(operation, manifest, "executor-b", "isolated", PublicationManifest.Operation.REVOKE));
            } else {
                assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(operation, manifest, "executor-b", "isolated", PublicationManifest.Operation.REVOKE));
            }
        }
    }
}
