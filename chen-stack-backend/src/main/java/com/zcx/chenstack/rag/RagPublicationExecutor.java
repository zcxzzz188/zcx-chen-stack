package com.zcx.chenstack.rag;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Trusted, default-off operator entry point; never exposed as a customer-service tool or write API. */
public final class RagPublicationExecutor {
    private final CatalogLifecycle lifecycle;
    private final RagKnowledgeService knowledge;
    private final PublicationManifest manifest;
    private final PublicationAuthorization.Grant grant;
    private final String approvalHash;
    private final List<KnowledgeDocument> drafts;

    public RagPublicationExecutor(CatalogLifecycle lifecycle, RagKnowledgeService knowledge,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String approvalHash,
            List<KnowledgeDocument> validatedDrafts) {
        this.lifecycle = lifecycle; this.knowledge = knowledge; this.manifest = manifest;
        this.grant = grant; this.approvalHash = approvalHash; this.drafts = List.copyOf(validatedDrafts);
    }

    /** Only a verified bootstrap receipt may initialize already-migrated, genuinely empty authority tables. */
    public static KnowledgeCatalog initialize(DataSource dataSource, List<KnowledgeDocument> allDrafts,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String approvalHash) {
        PublicationManifest.require(manifest.initializeEmptyCatalog() && manifest.expectedGeneration() == 0
                && "EMPTY".equals(manifest.expectedSnapshotSha256()), "RELEASE_BOOTSTRAP_NOT_APPROVED");
        JdbcCatalogStore.initializeApproved(dataSource, allDrafts, operation(manifest, grant, approvalHash));
        JdbcCatalogStore store = JdbcCatalogStore.open(dataSource);
        try { return KnowledgeCatalog.restore(store); }
        catch (RuntimeException error) { store.close(); throw error; }
    }

    public static KnowledgeCatalog openOrInitialize(DataSource dataSource, List<KnowledgeDocument> allDrafts,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String approvalHash) {
        boolean empty;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM rag_catalog_state)+(SELECT COUNT(*) FROM rag_source_state)+(SELECT COUNT(*) FROM rag_release_operation)")) {
            if (!result.next()) throw new IllegalStateException("RELEASE_SCHEMA_CHECK_FAILED");
            empty = result.getLong(1) == 0;
        } catch (java.sql.SQLException error) { throw new IllegalStateException("RELEASE_SCHEMA_MIGRATION_REQUIRED", error); }
        if (empty) return initialize(dataSource, allDrafts, manifest, grant, approvalHash);
        JdbcCatalogStore store = JdbcCatalogStore.open(dataSource);
        try { return KnowledgeCatalog.restore(store); }
        catch (RuntimeException error) { store.close(); throw error; }
    }

    public Map<String, Object> execute() {
        JdbcCatalogStore store = lifecycle.store();
        if (!lifecycle.beginPublication()) throw new IllegalStateException("RELEASE_ALREADY_RUNNING");
        boolean recorded = false;
        try {
            PublicationManifest.require(grant.expiresAt().isAfter(Instant.now())
                    && grant.allowedOperation() == manifest.operation()
                    && grant.environment().equals(manifest.environment()), "RELEASE_APPROVAL_EXPIRED_OR_MISMATCH");
            KnowledgeCatalog before = lifecycle.snapshot();
            validateSources(before);
            JdbcCatalogStore.ReleaseOperation previous = store.operation(grant.approvalId());
            if (previous != null) {
                PublicationManifest.require(previous.manifestHash().equals(grant.manifestSha256())
                        && previous.approvalHash().equals(approvalHash), "RELEASE_OPERATION_ID_REUSED");
            }
            Long applied = previous == null ? null : previous.appliedGeneration();
            if (applied != null) {
                PublicationManifest.require(applied == before.epoch()
                        && store.snapshotHash().equals(previous.appliedSnapshotHash()), "RELEASE_APPLIED_STATE_DRIFT");
            } else {
                PublicationManifest.require(manifest.expectedGeneration() == before.epoch()
                        && (manifest.expectedSnapshotSha256().equals(store.snapshotHash())
                        || manifest.initializeEmptyCatalog() && previous != null && before.epoch() == 0),
                        "RELEASE_CATALOG_PRECONDITION_MISMATCH");
            }
            if (previous != null && (("COMPLETED".equals(previous.status()) && knowledge.publicationReady()
                    && lifecycle.authorityId().equals(previous.indexAuthority()) && Objects.equals(before.epoch(), previous.indexEpoch()))
                    || "REVOKED".equals(previous.status()))) return result(previous.status());

            knowledge.blockPublication();
            store.prepareOperation(operation(manifest, grant, approvalHash));
            recorded = true;
            String owner = KnowledgeCatalog.hash("chen-release-v1|" + manifest.environment() + "|" + manifest.collection()
                    + "|" + store.databaseIdentityHash());
            if (manifest.operation() != PublicationManifest.Operation.REVOKE) {
                knowledge.preparePublicationTarget(manifest.createCollection(), owner);
            }
            if (applied == null && manifest.operation() != PublicationManifest.Operation.RECOVER) {
                store.checkpoint(grant.approvalId(), "VALIDATED", manifest.operation() != PublicationManifest.Operation.REVOKE, null, null);
                if (manifest.operation() == PublicationManifest.Operation.PUBLISH) {
                    PublicationManifest.require(drafts.stream().noneMatch(d -> "DOCUMENT_REVIEWED".equals(before.source(d.sourceId()).reviewStatus())),
                            "RELEASE_ALREADY_PUBLISHED_USE_RECOVER");
                    lifecycle.publish(drafts.stream().map(d -> new KnowledgeDocument(d.sourceId(), d.title(), d.text(), d.version(),
                            d.sourceType(), d.sourceUrl(), "PUBLIC", "DOCUMENT_REVIEWED", d.updatedAt(), d.keywords())).toList(),
                            before.epoch(), grant.approvalId());
                } else lifecycle.revokeApproved(manifest.revokeSourceId(), before.epoch(), grant.approvalId());
            }
            if (manifest.operation() == PublicationManifest.Operation.REVOKE) knowledge.preparePublicationTarget(false, owner);
            store.checkpoint(grant.approvalId(), "INDEXING", true, null, null);
            if (manifest.operation() == PublicationManifest.Operation.REVOKE) {
                knowledge.deleteSource(manifest.revokeSourceId());
                store.checkpoint(grant.approvalId(), "REVOKED", true, null, null);
                return result("REVOKED");
            }
            knowledge.publicationBudget(() -> {
                if (!grant.expiresAt().isAfter(Instant.now())) throw new SecurityException("RELEASE_APPROVAL_EXPIRED");
                store.reserveEmbedding(grant.approvalId());
            });
            knowledge.syncReviewed(false);
            knowledge.verifyPublicationIndex();
            KnowledgeCatalog verified = lifecycle.snapshot();
            store.checkpoint(grant.approvalId(), "COMPLETED", true, lifecycle.authorityId(), verified.epoch());
            knowledge.confirmPublication(verified);
            return result("COMPLETED");
        } catch (RuntimeException error) {
            knowledge.blockPublication();
            if (recorded) {
                try { store.failOperation(grant.approvalId(), "RELEASE_FAILED_CLOSED"); }
                catch (RuntimeException auditError) { error.addSuppressed(auditError); }
            }
            throw error;
        } finally {
            try { knowledge.publicationBudget(() -> {}); }
            catch (RuntimeException ignored) { /* An invalidated owner remains closed; no new work can be sent. */ }
            lifecycle.endPublication();
        }
    }

    private void validateSources(KnowledgeCatalog catalog) {
        Set<String> ids = drafts.stream().map(KnowledgeDocument::sourceId).collect(Collectors.toSet());
        PublicationManifest.require(ids.equals(PublicationManifest.userSourceIds()) && drafts.size() == 39, "RELEASE_SCOPE_INVALID");
        for (KnowledgeDocument draft : drafts) {
            KnowledgeDocument actual = catalog.source(draft.sourceId());
            PublicationManifest.require(actual != null && actual.version().equals(draft.version())
                    && KnowledgeCatalog.normalize(actual.text()).equals(KnowledgeCatalog.normalize(draft.text()))
                    && Objects.equals(actual.title(), draft.title()) && Objects.equals(actual.sourceType(), draft.sourceType())
                    && Objects.equals(actual.sourceUrl(), draft.sourceUrl()), "RELEASE_SOURCE_DRIFT");
            if (manifest.operation() == PublicationManifest.Operation.PUBLISH) {
                PublicationManifest.require(!"REVOKED".equals(actual.reviewStatus()), "RELEASE_SOURCE_REVOKED");
            }
        }
        PublicationManifest.require(catalog.documents().stream().filter(d -> !ids.contains(d.sourceId()))
                .noneMatch(d -> "PUBLIC".equals(d.visibility()) || "DOCUMENT_REVIEWED".equals(d.reviewStatus())), "RELEASE_EXCLUDED_SOURCE_APPROVED");
    }

    private Map<String, Object> result(String status) {
        return Map.of("operationId", grant.approvalId(), "status", status, "generation", lifecycle.snapshot().epoch());
    }

    private static JdbcCatalogStore.ReleaseOperation operation(PublicationManifest manifest,
            PublicationAuthorization.Grant grant, String approvalHash) {
        return new JdbcCatalogStore.ReleaseOperation(grant.approvalId(), grant.manifestSha256(), approvalHash,
                grant.environment(), grant.operator(), grant.approver(), manifest.operation().name(), "VALIDATED",
                manifest.expectedGeneration(), null, null, manifest.collection(), grant.maxEmbeddingRequests(),
                0, false, null, null, null);
    }
}
