package com.zcx.chenstack.rag;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

/** Trusted, default-off operator entry point; never exposed as a customer-service tool or write API. */
public final class RagPublicationExecutor {
    private final CatalogLifecycle lifecycle;
    private final RagKnowledgeService knowledge;
    private final PublicationManifest manifest;
    private final PublicationAuthorization.Grant grant;
    private final String operationHash;
    private final List<KnowledgeDocument> drafts;

    public RagPublicationExecutor(CatalogLifecycle lifecycle, RagKnowledgeService knowledge,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String operationHash,
            List<KnowledgeDocument> validatedDrafts) {
        this.lifecycle = lifecycle; this.knowledge = knowledge; this.manifest = manifest;
        this.grant = grant; this.operationHash = operationHash; this.drafts = List.copyOf(validatedDrafts);
    }

    /** Only an explicitly confirmed local operation may initialize migrated, genuinely empty authority tables. */
    public static KnowledgeCatalog initialize(DataSource dataSource, List<KnowledgeDocument> allDrafts,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String operationHash) {
        PublicationManifest.require(manifest.initializeEmptyCatalog() && manifest.expectedGeneration() == 0
                && "EMPTY".equals(manifest.expectedSnapshotSha256()), "RELEASE_BOOTSTRAP_NOT_APPROVED");
        JdbcCatalogStore.initializeApproved(dataSource, allDrafts, operation(manifest, grant, operationHash));
        JdbcCatalogStore store = JdbcCatalogStore.open(dataSource);
        try { return KnowledgeCatalog.restore(store); }
        catch (RuntimeException error) { store.close(); throw error; }
    }

    public static KnowledgeCatalog openOrInitialize(DataSource dataSource, List<KnowledgeDocument> allDrafts,
            PublicationManifest manifest, PublicationAuthorization.Grant grant, String operationHash) {
        boolean empty;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM rag_catalog_state)+(SELECT COUNT(*) FROM rag_source_state)+(SELECT COUNT(*) FROM rag_release_operation)")) {
            if (!result.next()) throw new IllegalStateException("RELEASE_SCHEMA_CHECK_FAILED");
            empty = result.getLong(1) == 0;
        } catch (java.sql.SQLException error) { throw new IllegalStateException("RELEASE_SCHEMA_MIGRATION_REQUIRED", error); }
        if (empty) return initialize(dataSource, allDrafts, manifest, grant, operationHash);
        JdbcCatalogStore store = JdbcCatalogStore.open(dataSource);
        try { return KnowledgeCatalog.restore(store); }
        catch (RuntimeException error) { store.close(); throw error; }
    }

    public Map<String, Object> execute() {
        JdbcCatalogStore store = lifecycle.store();
        if (!lifecycle.beginPublication()) throw new IllegalStateException("RELEASE_ALREADY_RUNNING");
        boolean recorded = false;
        try {
            PublicationAuthorization.requireGrant(grant, grant.operator(), manifest.environment(), manifest.operation());
            boolean reuseIndex = manifest.operation() == PublicationManifest.Operation.RECOVER && grant.maxEmbeddingRequests() == 0;
            if (reuseIndex) PublicationManifest.require(!manifest.createCollection() && !manifest.initializeEmptyCatalog(),
                    "RECOVERY_ZERO_BUDGET_CANNOT_INITIALIZE");
            KnowledgeCatalog before = lifecycle.snapshot();
            validateSources(before);
            JdbcCatalogStore.ReleaseOperation previous = store.operation(grant.operationId());
            if (previous != null) {
                PublicationManifest.require(previous.manifestHash().equals(grant.manifestSha256())
                        && previous.operationHash().equals(operationHash)
                        && PublicationAuthorization.MODE.equals(previous.authorizationMode())
                        && previous.operator().equals(grant.operator()) && previous.environment().equals(grant.environment())
                        && previous.type().equals(manifest.operation().name()) && previous.collection().equals(manifest.collection())
                        && previous.expectedGeneration() == manifest.expectedGeneration()
                        && previous.maxEmbeddingRequests() == grant.maxEmbeddingRequests(), "RELEASE_OPERATION_ID_REUSED");
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
            if (previous != null && (("COMPLETED".equals(previous.status())
                    && knowledge.publicationMatches(previous.indexAuthority(), previous.indexEpoch()) && Objects.equals(before.epoch(), previous.indexEpoch()))
                    || "REVOKED".equals(previous.status()))) return result(previous.status());
            PublicationManifest.require(previous == null || !"COMPLETED".equals(previous.status()),
                    "RELEASE_COMPLETED_USE_READ_ONLY_RECOVERY");

            knowledge.blockPublication();
            store.prepareOperation(operation(manifest, grant, operationHash));
            recorded = true;
            String owner = KnowledgeCatalog.hash("chen-release-v1|" + manifest.environment() + "|" + manifest.collection()
                    + "|" + store.databaseIdentityHash());
            if (manifest.operation() != PublicationManifest.Operation.REVOKE) {
                knowledge.preparePublicationTarget(manifest.createCollection(), owner);
            }
            if (reuseIndex) {
                var receipt = store.latestCompletedReceipt(manifest.environment(), manifest.collection());
                var indexed = receipt.operation();
                PublicationManifest.require(!indexed.manifestHash().equals(grant.manifestSha256())
                        && indexed.targetInitialized() && indexed.indexAuthority() != null
                        && indexed.indexAuthority().matches("[0-9a-f-]{36}")
                        && indexed.operator().equals(grant.operator())
                        && indexed.maxEmbeddingRequests() >= 0 && indexed.maxEmbeddingRequests() <= Integer.MAX_VALUE
                        && indexed.embeddingRequests() >= 0 && indexed.embeddingRequests() <= indexed.maxEmbeddingRequests()
                        && before.epoch() == ("PUBLISH".equals(indexed.type())
                            ? Math.incrementExact(indexed.expectedGeneration()) : indexed.expectedGeneration())
                        && Objects.equals(indexed.indexEpoch(), before.epoch())
                        && ("PUBLISH".equals(indexed.type()) || "RECOVER".equals(indexed.type()))
                        && PublicationManifest.validHash(indexed.operationHash())
                        && !receipt.completedAt().isAfter(java.time.Instant.now()), "RECOVERY_INDEX_RECEIPT_MISMATCH");
                String indexHash = knowledge.verifyPublicationIndex(indexed.indexAuthority(), indexed.indexEpoch());
                PublicationManifest.require(receipt.indexHash().equals(indexHash), "RECOVERY_INDEX_DIGEST_MISMATCH");
                // 只新增本次运维回执，复用旧向量的 authority/epoch；不重新发布、嵌入或写索引。
                store.checkpoint(grant.operationId(), "COMPLETED", true, indexed.indexAuthority(), indexed.indexEpoch(), indexHash);
                knowledge.confirmPublication(before, indexed.indexAuthority(), indexed.indexEpoch());
                return result("COMPLETED");
            }
            if (applied == null && manifest.operation() != PublicationManifest.Operation.RECOVER) {
                store.checkpoint(grant.operationId(), "VALIDATED", manifest.operation() != PublicationManifest.Operation.REVOKE, null, null);
                if (manifest.operation() == PublicationManifest.Operation.PUBLISH) {
                    PublicationManifest.require(drafts.stream().noneMatch(d -> "DOCUMENT_REVIEWED".equals(before.source(d.sourceId()).reviewStatus())),
                            "RELEASE_ALREADY_PUBLISHED_USE_RECOVER");
                    lifecycle.publish(drafts.stream().map(d -> new KnowledgeDocument(d.sourceId(), d.title(), d.text(), d.version(),
                            d.sourceType(), d.sourceUrl(), "PUBLIC", "DOCUMENT_REVIEWED", d.updatedAt(), d.keywords())).toList(),
                            before.epoch(), grant.operationId());
                } else lifecycle.revokeApproved(manifest.revokeSourceId(), before.epoch(), grant.operationId());
            }
            if (manifest.operation() == PublicationManifest.Operation.REVOKE) knowledge.preparePublicationTarget(false, owner);
            store.checkpoint(grant.operationId(), "INDEXING", true, null, null);
            if (manifest.operation() == PublicationManifest.Operation.REVOKE) {
                knowledge.deleteSource(manifest.revokeSourceId());
                store.checkpoint(grant.operationId(), "REVOKED", true, null, null);
                return result("REVOKED");
            }
            knowledge.publicationBudget(() -> {
                store.reserveEmbedding(grant.operationId());
            });
            knowledge.syncReviewed(false);
            String indexHash = knowledge.verifyPublicationIndex();
            KnowledgeCatalog verified = lifecycle.snapshot();
            store.checkpoint(grant.operationId(), "COMPLETED", true, lifecycle.authorityId(), verified.epoch(), indexHash);
            knowledge.confirmPublication(verified);
            return result("COMPLETED");
        } catch (RuntimeException error) {
            knowledge.blockPublication();
            if (recorded) {
                try { store.failOperation(grant.operationId(), "RELEASE_FAILED_CLOSED"); }
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
                    && Objects.equals(actual.sourceUrl(), draft.sourceUrl()) && Objects.equals(actual.keywords(), draft.keywords())
                    && Objects.equals(actual.updatedAt(), draft.updatedAt()), "RELEASE_SOURCE_DRIFT");
            if (manifest.operation() == PublicationManifest.Operation.PUBLISH) {
                PublicationManifest.require(!"REVOKED".equals(actual.reviewStatus()), "RELEASE_SOURCE_REVOKED");
            }
        }
        PublicationManifest.require(catalog.documents().stream().filter(d -> !ids.contains(d.sourceId()))
                .noneMatch(d -> "PUBLIC".equals(d.visibility()) || "DOCUMENT_REVIEWED".equals(d.reviewStatus())), "RELEASE_EXCLUDED_SOURCE_APPROVED");
    }

    private Map<String, Object> result(String status) {
        return Map.of("operationId", grant.operationId(), "status", status, "generation", lifecycle.snapshot().epoch());
    }

    private static JdbcCatalogStore.ReleaseOperation operation(PublicationManifest manifest,
            PublicationAuthorization.Grant grant, String operationHash) {
        PublicationAuthorization.requireGrant(grant, grant.operator(), manifest.environment(), manifest.operation());
        PublicationManifest.require(manifest.operation() != PublicationManifest.Operation.RECOVER || grant.maxEmbeddingRequests() != 0
                || !manifest.initializeEmptyCatalog() && !manifest.createCollection(), "RECOVERY_ZERO_BUDGET_CANNOT_INITIALIZE");
        PublicationManifest.require(PublicationManifest.validHash(operationHash), "RELEASE_OPERATION_HASH_INVALID");
        return new JdbcCatalogStore.ReleaseOperation(grant.operationId(), grant.manifestSha256(), operationHash,
                grant.environment(), grant.operator(), PublicationAuthorization.MODE, manifest.operation().name(), "VALIDATED",
                manifest.expectedGeneration(), null, null, manifest.collection(), grant.maxEmbeddingRequests(),
                0, false, null, null, null);
    }
}
