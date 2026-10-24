package com.zcx.chenstack.rag;

/** 本地受控命令的操作记录校验；文件权限和部署权限是信任边界，SHA256不是数字签名。 */
public final class PublicationAuthorization {
    public static final String MODE = "CONTROLLED_OPERATOR";

    private PublicationAuthorization() { }

    public record Grant(int schemaVersion, String operationId, String operator, String environment,
                        String manifestSha256, int maxEmbeddingRequests,
                        PublicationManifest.Operation allowedOperation, boolean confirmed) { }

    public static Grant verify(byte[] operationBytes, byte[] manifestBytes, String runtimeOperator,
                               String runtimeEnvironment, PublicationManifest.Operation operation) {
        PublicationManifest.require(PublicationManifest.safeText(runtimeOperator, 128)
                && PublicationManifest.validEnvironment(runtimeEnvironment) && operation != null,
                "PUBLICATION_OPERATOR_REQUIRED");
        Grant grant = PublicationManifest.readJson(operationBytes, Grant.class, 8_192);
        requireGrant(grant, runtimeOperator, runtimeEnvironment, operation);
        PublicationManifest.require(manifestBytes != null && manifestBytes.length > 0 && manifestBytes.length <= 65_536
                && PublicationManifest.sha256(manifestBytes).equals(grant.manifestSha256()),
                "PUBLICATION_OPERATION_MANIFEST_MISMATCH");
        PublicationManifest manifest = PublicationManifest.parse(manifestBytes);
        PublicationManifest.require(manifest.operation() == operation && runtimeEnvironment.equals(manifest.environment()),
                "PUBLICATION_OPERATION_REJECTED");
        return grant;
    }

    static void requireGrant(Grant grant, String operator, String environment, PublicationManifest.Operation operation) {
        PublicationManifest.require(grant != null && grant.schemaVersion() == 1 && grant.confirmed()
                && grant.operationId() != null && grant.operationId().matches("[A-Za-z0-9._-]{1,80}")
                && PublicationManifest.safeText(grant.operator(), 128)
                && PublicationManifest.validEnvironment(grant.environment())
                && grant.operator().equals(operator) && grant.environment().equals(environment)
                && PublicationManifest.validHash(grant.manifestSha256()) && grant.allowedOperation() == operation
                && grant.maxEmbeddingRequests() >= 0
                && (operation != PublicationManifest.Operation.REVOKE || grant.maxEmbeddingRequests() == 0),
                "PUBLICATION_OPERATION_REJECTED");
    }
}
