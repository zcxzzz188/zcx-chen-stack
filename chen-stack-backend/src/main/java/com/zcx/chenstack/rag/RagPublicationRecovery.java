package com.zcx.chenstack.rag;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 正常重启只读恢复；新发布、索引修复及版本升级仍走限时签批执行器。 */
public final class RagPublicationRecovery {
    private RagPublicationRecovery() { }

    public static Map<String, Object> restore(CatalogLifecycle lifecycle, RagKnowledgeService knowledge,
            byte[] manifestBytes, byte[] approvalBytes, PublicKey trustedKey, String operator, String environment,
            PublicationManifest.RuntimeIdentity runtime, Map<String, byte[]> resources) {
        if (!lifecycle.beginPublication()) throw new IllegalStateException("RELEASE_ALREADY_RUNNING");
        knowledge.blockPublication();
        try {
            PublicationManifest manifest = PublicationManifest.parse(manifestBytes);
            List<KnowledgeDocument> drafts = manifest.validateResources(runtime, resources);
            JdbcCatalogStore store = lifecycle.store();
            var receipt = store.completedReceipt(PublicationManifest.sha256(manifestBytes), PublicationManifest.sha256(approvalBytes));
            var grant = PublicationAuthorization.verify(approvalBytes, manifestBytes, trustedKey, operator, environment,
                    manifest.operation(), receipt.validatedAt());
            var operation = receipt.operation();
            KnowledgeCatalog catalog = lifecycle.snapshot();
            PublicationManifest.require(manifest.operation() != PublicationManifest.Operation.REVOKE
                    && receipt.completedAt().isBefore(grant.expiresAt()) && !receipt.completedAt().isAfter(Instant.now())
                    && operation.id().equals(grant.approvalId()) && operation.type().equals(manifest.operation().name())
                    && operation.environment().equals(environment) && operation.operator().equals(operator)
                    && operation.approver().equals(grant.approver()) && operation.collection().equals(manifest.collection())
                    && operation.maxEmbeddingRequests() == grant.maxEmbeddingRequests()
                    && operation.expectedGeneration() == manifest.expectedGeneration()
                    && catalog.epoch() == (manifest.operation() == PublicationManifest.Operation.PUBLISH
                        ? Math.incrementExact(manifest.expectedGeneration()) : manifest.expectedGeneration())
                    && operation.embeddingRequests() >= 0 && operation.embeddingRequests() <= operation.maxEmbeddingRequests()
                    && operation.targetInitialized() && operation.indexAuthority() != null
                    && operation.indexAuthority().matches("[0-9a-f-]{36}")
                    && Objects.equals(operation.appliedGeneration(), catalog.epoch())
                    && Objects.equals(operation.indexEpoch(), catalog.epoch())
                    && store.snapshotHash().equals(operation.appliedSnapshotHash()), "RECOVERY_RECEIPT_STATE_MISMATCH");
            Set<String> approvedIds = PublicationManifest.userSourceIds();
            for (KnowledgeDocument draft : drafts) {
                KnowledgeDocument actual = catalog.source(draft.sourceId());
                PublicationManifest.require(actual != null && actual.version().equals(draft.version())
                        && KnowledgeCatalog.normalize(actual.text()).equals(KnowledgeCatalog.normalize(draft.text()))
                        && Objects.equals(actual.title(), draft.title()) && Objects.equals(actual.sourceType(), draft.sourceType())
                        && Objects.equals(actual.sourceUrl(), draft.sourceUrl()) && Objects.equals(actual.keywords(), draft.keywords())
                        && Objects.equals(actual.updatedAt(), draft.updatedAt())
                        && ("REVOKED".equals(actual.reviewStatus())
                            || "PUBLIC".equals(actual.visibility()) && "DOCUMENT_REVIEWED".equals(actual.reviewStatus())),
                        "RECOVERY_SOURCE_DRIFT");
            }
            PublicationManifest.require(catalog.documents().stream().filter(d -> !approvedIds.contains(d.sourceId()))
                    .noneMatch(d -> "PUBLIC".equals(d.visibility()) || "DOCUMENT_REVIEWED".equals(d.reviewStatus())),
                    "RECOVERY_EXCLUDED_SOURCE_APPROVED");
            String owner = KnowledgeCatalog.hash("chen-release-v1|" + manifest.environment() + "|" + manifest.collection()
                    + "|" + store.databaseIdentityHash());
            knowledge.preparePublicationTarget(false, owner);
            String fingerprint = knowledge.verifyPublicationIndex(operation.indexAuthority(), operation.indexEpoch());
            PublicationManifest.require(receipt.indexHash().equals(fingerprint), "RECOVERY_INDEX_DIGEST_MISMATCH");
            store.requireUnchanged();
            knowledge.confirmPublication(catalog, operation.indexAuthority(), operation.indexEpoch());
            return Map.of("status", "READ_ONLY_RECOVERED", "operationId", operation.id(), "generation", catalog.epoch());
        } catch (RuntimeException error) {
            knowledge.blockPublication();
            throw error;
        } finally { lifecycle.endPublication(); }
    }
}
