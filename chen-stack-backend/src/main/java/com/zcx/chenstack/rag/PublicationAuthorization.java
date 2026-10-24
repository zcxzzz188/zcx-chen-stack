package com.zcx.chenstack.rag;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/** 仅验证外部审批；默认关闭和公钥信任来源由调用方配置控制。 */
public final class PublicationAuthorization {
    private PublicationAuthorization() { }

    public record Grant(String approvalId, String approver, String operator, String environment,
                        String manifestSha256, Instant expiresAt, int maxEmbeddingRequests,
                        PublicationManifest.Operation allowedOperation) { }

    private record Envelope(String payload, String signature) { }

    public static Grant verify(byte[] envelopeBytes, byte[] manifestBytes, PublicKey trustedPublicKey,
                               String runtimeOperator, String runtimeEnvironment,
                               PublicationManifest.Operation operation, Instant now) {
        PublicationManifest.require(trustedPublicKey != null && now != null && operation != null
                && PublicationManifest.safeText(runtimeOperator, 128)
                && PublicationManifest.validEnvironment(runtimeEnvironment), "PUBLICATION_AUTHORIZATION_REQUIRED");
        Envelope envelope = PublicationManifest.readJson(envelopeBytes, Envelope.class, 16_384);
        byte[] payload = decode(envelope.payload(), 8_192);
        byte[] signature = decode(envelope.signature(), 64);
        PublicationManifest.require(signature.length == 64, "PUBLICATION_SIGNATURE_INVALID");
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedPublicKey);
            verifier.update(payload);
            PublicationManifest.require(verifier.verify(signature), "PUBLICATION_SIGNATURE_INVALID");
        } catch (GeneralSecurityException exception) {
            throw new SecurityException("PUBLICATION_SIGNATURE_INVALID");
        }
        // 先验证原始 payload 签名，之后才解释身份、权限与额度字段。
        Grant grant = PublicationManifest.readJson(payload, Grant.class, 8_192);
        PublicationManifest.require(grant.approvalId() != null && grant.approvalId().matches("[A-Za-z0-9._-]{1,80}")
                && PublicationManifest.safeText(grant.approver(), 128)
                && PublicationManifest.safeText(grant.operator(), 128)
                && !grant.approver().equals(grant.operator())
                && runtimeOperator.equals(grant.operator()) && runtimeEnvironment.equals(grant.environment())
                && grant.expiresAt() != null && grant.expiresAt().isAfter(now)
                && grant.allowedOperation() == operation && grant.maxEmbeddingRequests() >= 0
                && (operation != PublicationManifest.Operation.REVOKE || grant.maxEmbeddingRequests() == 0),
                "PUBLICATION_GRANT_REJECTED");
        PublicationManifest.require(manifestBytes != null && manifestBytes.length > 0 && manifestBytes.length <= 65_536
                && PublicationManifest.validHash(grant.manifestSha256())
                && PublicationManifest.sha256(manifestBytes).equals(grant.manifestSha256()),
                "PUBLICATION_APPROVAL_MANIFEST_MISMATCH");
        PublicationManifest manifest = PublicationManifest.parse(manifestBytes);
        PublicationManifest.require(manifest.operation() == operation && runtimeEnvironment.equals(manifest.environment()),
                "PUBLICATION_GRANT_REJECTED");
        return grant;
    }

    private static byte[] decode(String value, int maximumBytes) {
        PublicationManifest.require(value != null && !value.isEmpty() && value.length() <= maximumBytes * 2,
                "PUBLICATION_ENVELOPE_INVALID");
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            PublicationManifest.require(decoded.length > 0 && decoded.length <= maximumBytes
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value),
                    "PUBLICATION_ENVELOPE_INVALID");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new SecurityException("PUBLICATION_ENVELOPE_INVALID");
        }
    }
}
