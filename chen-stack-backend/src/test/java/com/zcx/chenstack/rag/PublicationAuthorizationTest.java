package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicationAuthorizationTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static final byte[] MANIFEST = manifest(PublicationManifest.Operation.PUBLISH, "isolated");
    private KeyPair key;

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

    @BeforeEach void ephemeralTestKey() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private PublicationAuthorization.Grant grant() {
        return new PublicationAuthorization.Grant("synthetic-approval", "reviewer-a", "executor-b", "isolated",
                PublicationManifest.sha256(MANIFEST), NOW.plusSeconds(60), 3, PublicationManifest.Operation.PUBLISH);
    }

    private byte[] sign(byte[] payload) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate()); signer.update(payload);
        return JSON.writeValueAsBytes(Map.of("payload", encode(payload), "signature", encode(signer.sign())));
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private PublicationAuthorization.Grant verify(byte[] envelope, byte[] manifest) {
        return PublicationAuthorization.verify(envelope, manifest, key.getPublic(), "executor-b", "isolated",
                PublicationManifest.Operation.PUBLISH, NOW);
    }

    private byte[] changedGrant(String field, Object value) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(grant());
        node.set(field, JSON.valueToTree(value));
        return sign(JSON.writeValueAsBytes(node));
    }

    @Test void acceptsExternalTrustedPublicKeyAndReturnsSignedBudget() throws Exception {
        assertEquals(grant(), verify(sign(JSON.writeValueAsBytes(grant())), MANIFEST));
    }

    @Test void deniesMissingApprovalOrTrustKey() throws Exception {
        assertThrows(SecurityException.class, () -> verify(null, MANIFEST));
        byte[] envelope = sign(JSON.writeValueAsBytes(grant()));
        assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(envelope, MANIFEST, null,
                "executor-b", "isolated", PublicationManifest.Operation.PUBLISH, NOW));
    }

    @Test void rejectsWrongKeyTamperedPayloadAndSignature() throws Exception {
        byte[] envelope = sign(JSON.writeValueAsBytes(grant()));
        var otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(envelope, MANIFEST, otherKey.getPublic(),
                "executor-b", "isolated", PublicationManifest.Operation.PUBLISH, NOW));
        for (String field : List.of("payload", "signature")) {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(envelope);
            byte[] bytes = Base64.getUrlDecoder().decode(node.get(field).asText()); bytes[0] ^= 1;
            node.put(field, encode(bytes));
            assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(node), MANIFEST));
        }
    }

    @Test void rejectsSelfApprovalWrongOperatorEnvironmentAndOperation() throws Exception {
        for (var field : Map.of("approver", "executor-b", "operator", "other-executor", "environment", "production",
                "allowedOperation", "RECOVER").entrySet()) {
            assertThrows(SecurityException.class, () -> verify(changedGrant(field.getKey(), field.getValue()), MANIFEST));
        }
    }

    @Test void rejectsExpiryAtOrBeforeCurrentTime() throws Exception {
        for (Instant expiry : List.of(NOW, NOW.minusSeconds(1))) {
            assertThrows(SecurityException.class, () -> verify(changedGrant("expiresAt", expiry), MANIFEST));
        }
    }

    @Test void rejectsNegativeBudgetMissingIdentityAndUnknownSignedFields() throws Exception {
        assertThrows(SecurityException.class, () -> verify(changedGrant("maxEmbeddingRequests", -1), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("approvalId", " "), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("approvalId", "a".repeat(81)), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("approvalId", "审批-1"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("approver", "reviewer\nsecret"), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(changedGrant("unexpected", "not-allowed"), MANIFEST));
    }

    @Test void bindsApprovalToExactRawManifestBytes() throws Exception {
        byte[] envelope = sign(JSON.writeValueAsBytes(grant()));
        byte[] changed = (new String(MANIFEST, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class, () -> verify(envelope, changed));
        assertThrows(SecurityException.class, () -> verify(changedGrant("manifestSha256", "a".repeat(64)), MANIFEST));
    }

    @Test void operationAndEnvironmentMustAlsoMatchTheSignedManifest() throws Exception {
        for (byte[] bytes : List.of(manifest(PublicationManifest.Operation.REVOKE, "isolated"),
                manifest(PublicationManifest.Operation.PUBLISH, "production"))) {
            assertThrows(SecurityException.class, () -> verify(changedGrant("manifestSha256", PublicationManifest.sha256(bytes)), bytes));
        }
    }

    @Test void deniesMalformedNonCanonicalOrOversizedEnvelope() throws Exception {
        for (String json : List.of("{}", "{\"payload\":\"not+base64\",\"signature\":\"a\"}",
                "{\"payload\":\"e30=\",\"signature\":\"a\"}",
                "{\"payload\":\"e30\",\"payload\":\"e30\",\"signature\":\"a\"}", "null")) {
            assertThrows(SecurityException.class, () -> verify(json.getBytes(StandardCharsets.UTF_8), MANIFEST));
        }
        assertThrows(SecurityException.class, () -> verify(new byte[16_385], MANIFEST));
        assertThrows(SecurityException.class, () -> verify(sign("{} {}".getBytes(StandardCharsets.UTF_8)), MANIFEST));
        assertThrows(SecurityException.class, () -> verify(sign("{}".getBytes(StandardCharsets.UTF_16)), MANIFEST));
    }

    @Test void revokeApprovalRequiresZeroEmbeddingBudget() throws Exception {
        byte[] manifest = manifest(PublicationManifest.Operation.REVOKE, "isolated");
        for (int budget : List.of(0, 1)) {
            var grant = new PublicationAuthorization.Grant("revoke-approval", "reviewer-a", "executor-b", "isolated",
                    PublicationManifest.sha256(manifest), NOW.plusSeconds(1), budget, PublicationManifest.Operation.REVOKE);
            byte[] envelope = sign(JSON.writeValueAsBytes(grant));
            if (budget == 0) {
                assertEquals(grant, PublicationAuthorization.verify(envelope, manifest, key.getPublic(), "executor-b", "isolated",
                        PublicationManifest.Operation.REVOKE, NOW));
            } else {
                assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(envelope, manifest, key.getPublic(),
                        "executor-b", "isolated", PublicationManifest.Operation.REVOKE, NOW));
            }
        }
    }
}
