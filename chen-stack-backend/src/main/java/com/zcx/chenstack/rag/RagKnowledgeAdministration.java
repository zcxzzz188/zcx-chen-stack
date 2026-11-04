package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated management uses the existing authority and audit, independently of local release grants. */
public final class RagKnowledgeAdministration {
    static final String AUTHORIZATION = "AUTHENTICATED_KNOWLEDGE_ADMIN";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final CatalogLifecycle lifecycle;
    private final RagKnowledgeService knowledge;

    public RagKnowledgeAdministration(CatalogLifecycle lifecycle, RagKnowledgeService knowledge) {
        this.lifecycle = lifecycle; this.knowledge = knowledge;
    }

    public record Result(String operationId, String status, long generation) { }
    public record PendingOperation(String operationId, String sourceId, String action, String status, long generation) { }

    public List<PendingOperation> pendingOperations() {
        JdbcCatalogStore store = lifecycle.store();
        try {
            var chain = store.managementChain(store.latestControlledReceipt(knowledge.managementCollection()));
            if (chain.complete()) return List.of();
            var pending = chain.effective().operation();
            return List.of(new PendingOperation(pending.id(), store.managedSource(pending.id()),
                    switch (pending.type()) {
                        case "KNOWLEDGE_REVOKE" -> "REVOKE";
                        case "KNOWLEDGE_CREATE" -> "CREATE";
                        default -> "UPDATE";
                    }, pending.status(), pending.appliedGeneration()));
        } catch (RuntimeException invalid) { knowledge.blockPublication(); throw invalid; }
    }

    /** Resume only the already committed, verified content; no replacement body or authorization is accepted. */
    public Result resume(String operationId, int actorId) {
        if (operationId == null || !operationId.matches("manage-[A-Za-z0-9._-]{1,72}") || actorId <= 0) {
            throw new IllegalArgumentException("MANAGEMENT_RECOVERY_INVALID");
        }
        JdbcCatalogStore store = lifecycle.store();
        if (!lifecycle.beginPublication()) throw new IllegalStateException("RELEASE_ALREADY_RUNNING");
        boolean recorded = false;
        try {
            knowledge.requireReady();
            var anchor = store.latestControlledReceipt(knowledge.managementCollection());
            JdbcCatalogStore.ManagementChain chain;
            try { chain = store.managementChain(anchor); }
            catch (RuntimeException invalid) { knowledge.blockPublication(); throw invalid; }
            var operation = chain.effective().operation();
            if (!operation.id().equals(operationId) || !AUTHORIZATION.equals(operation.authorizationMode())) {
                throw new IllegalStateException("MANAGEMENT_RECOVERY_SCOPE_INVALID");
            }
            if (chain.complete()) {
                verifyIndex(anchor, chain.effective());
                knowledge.confirmPublication(lifecycle.snapshot(), operation.indexAuthority(), operation.indexEpoch());
                return new Result(operationId, "COMPLETED", lifecycle.snapshot().epoch());
            }
            store.recordManagementRecovery(operationId, actorId); recorded = true;
            knowledge.blockPublication();
            return completeOperation(operationId, store.managedSource(operationId), "KNOWLEDGE_REVOKE".equals(operation.type()), anchor);
        } catch (RuntimeException failure) {
            if (recorded) { knowledge.blockPublication(); failUnlessCompleted(store, operationId, failure); }
            throw failure;
        } finally { releasePublication(); }
    }

    public Result update(KnowledgeDocument replacement, long expectedEpoch, String expectedContentHash,
                         String requestId, int actorId) {
        if (replacement == null) throw new IllegalArgumentException("MANAGEMENT_INPUT_INVALID");
        return change(replacement.sourceId(), replacement, expectedEpoch, expectedContentHash, requestId, actorId, false);
    }

    /** 人工新增知识：仍由已认证管理员操作，进入同一目录、索引与回执链。 */
    public Result create(KnowledgeDocument addition, long expectedEpoch, String requestId, int actorId) {
        if (addition == null) throw new IllegalArgumentException("MANAGEMENT_INPUT_INVALID");
        return change(addition.sourceId(), addition, expectedEpoch,
                KnowledgeCatalog.hash(KnowledgeCatalog.normalize(addition.text())), requestId, actorId, true);
    }

    public Result revoke(String sourceId, long expectedEpoch, String expectedContentHash, String requestId, int actorId) {
        return change(sourceId, null, expectedEpoch, expectedContentHash, requestId, actorId, false);
    }

    /**
     * 仅用于把已完成但客户端未收到响应的新增操作补记为成功；不改变正文、索引或授权。
     * 调用方仍需自行更新业务草稿状态。
     */
    public Result completedCreate(String requestId, String sourceId) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,72}")
                || sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}")) return null;
        JdbcCatalogStore store = lifecycle.store();
        String id = "manage-" + requestId;
        try {
            JdbcCatalogStore.ReleaseOperation operation = store.operation(id);
            if (operation == null || !"KNOWLEDGE_CREATE".equals(operation.type())
                    || !AUTHORIZATION.equals(operation.authorizationMode()) || !"COMPLETED".equals(operation.status())
                    || operation.appliedGeneration() == null || !operation.targetInitialized()
                    || operation.indexAuthority() == null || !Objects.equals(operation.indexEpoch(), operation.appliedGeneration())
                    || !sourceId.equals(store.managedSource(id))) {
                return null;
            }
            KnowledgeCatalog current = lifecycle.snapshot();
            if (operation.appliedGeneration() != current.epoch()) return null;
            knowledge.confirmPublication(current, operation.indexAuthority(), operation.indexEpoch());
            return new Result(id, "COMPLETED", current.epoch());
        } finally {
            store.rollbackQuietly();
        }
    }

    private Result change(String sourceId, KnowledgeDocument replacement, long expectedEpoch, String expectedHash,
                          String requestId, int actorId, boolean create) {
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}") || sourceId.startsWith("help-admin-")
                || expectedEpoch < 0 || actorId <= 0
                || !PublicationManifest.validHash(expectedHash) || requestId == null
                || !requestId.matches("[A-Za-z0-9._-]{1,72}")) throw new IllegalArgumentException("MANAGEMENT_INPUT_INVALID");
        String id = "manage-" + requestId;
        String type = create ? "KNOWLEDGE_CREATE" : replacement == null ? "KNOWLEDGE_REVOKE" : "KNOWLEDGE_UPDATE";
        String requestHash = requestHash(sourceId, replacement, expectedEpoch, expectedHash, actorId);
        JdbcCatalogStore store = lifecycle.store();
        if (!lifecycle.beginPublication()) throw new IllegalStateException("RELEASE_ALREADY_RUNNING");
        boolean blocked = false;
        boolean recorded = false;
        try {
            knowledge.requireReady();
            KnowledgeCatalog current = lifecycle.snapshot();
            JdbcCatalogStore.ReleaseReceipt anchor = store.latestControlledReceipt(knowledge.managementCollection());
            JdbcCatalogStore.ManagementChain chain;
            try { chain = store.managementChain(anchor); }
            catch (RuntimeException invalid) { knowledge.blockPublication(); throw invalid; }
            JdbcCatalogStore.ReleaseOperation previous = store.operation(id);
            if (previous != null && (!AUTHORIZATION.equals(previous.authorizationMode())
                    || !requestHash.equals(previous.operationHash()) || !type.equals(previous.type())
                    || !previous.operator().equals("user:" + actorId) || previous.expectedGeneration() != expectedEpoch
                    || !previous.manifestHash().equals(anchor.operation().manifestHash()))) {
                throw new IllegalStateException("MANAGEMENT_OPERATION_REPLAY");
            }
            if (previous != null && previous.appliedGeneration() != null) {
                if (previous.appliedGeneration() != current.epoch() || !store.snapshotHash().equals(previous.appliedSnapshotHash())) {
                    throw new IllegalStateException("MANAGEMENT_APPLIED_STATE_DRIFT");
                }
                if ("COMPLETED".equals(previous.status())) {
                    verifyIndex(anchor, chain.effective());
                    knowledge.confirmPublication(current, previous.indexAuthority(), previous.indexEpoch());
                    return new Result(id, "COMPLETED", current.epoch());
                }
            } else {
                if (!chain.complete()) throw new IllegalStateException("MANAGEMENT_PREVIOUS_OPERATION_PENDING");
                knowledge.reviewedCatalog();
                verifyIndex(anchor, chain.effective());
                if (create) {
                    if (replacement == null || current.source(sourceId) != null || current.epoch() != expectedEpoch) {
                        throw new IllegalStateException("MANAGEMENT_SOURCE_CONFLICT");
                    }
                    validateAddition(replacement);
                } else {
                    KnowledgeDocument old = current.source(sourceId);
                    if (old == null || current.epoch() != expectedEpoch
                            || !expectedHash.equals(KnowledgeCatalog.hash(KnowledgeCatalog.normalize(old.text())))
                            || !"PUBLIC".equals(old.visibility()) || !"DOCUMENT_REVIEWED".equals(old.reviewStatus())) {
                        throw new IllegalStateException("MANAGEMENT_SOURCE_CONFLICT");
                    }
                    if (replacement != null) validateReplacement(old, replacement);
                }
                if (previous == null) {
                    store.prepareOperation(new JdbcCatalogStore.ReleaseOperation(id, anchor.operation().manifestHash(), requestHash,
                            anchor.operation().environment(), "user:" + actorId, AUTHORIZATION, type, "VALIDATED",
                            expectedEpoch, null, null, anchor.operation().collection(), replacement == null ? 0 : Long.MAX_VALUE,
                            0, false, null, null, null));
                }
                store.bindManagement(id, store.snapshotHash(), sourceId);
                store.resumeUnappliedManagement(id);
                recorded = true;
                knowledge.blockPublication(); blocked = true;
                if (create) lifecycle.add(replacement, expectedEpoch);
                else if (replacement == null) lifecycle.revokeApproved(sourceId, expectedEpoch, id);
                else lifecycle.publish(List.of(replacement), expectedEpoch, id);
            }
            recorded = true;
            knowledge.blockPublication(); blocked = true;
            return completeOperation(id, sourceId, replacement == null, anchor);
        } catch (RuntimeException failure) {
            if (blocked) knowledge.blockPublication();
            if (recorded) failUnlessCompleted(store, id, failure);
            throw failure;
        } finally { releasePublication(); }
    }

    private Result completeOperation(String id, String sourceId, boolean revoked, JdbcCatalogStore.ReleaseReceipt anchor) {
        JdbcCatalogStore store = lifecycle.store();
        String owner = KnowledgeCatalog.hash("chen-release-v1|" + anchor.operation().environment() + "|"
                + anchor.operation().collection() + "|" + store.databaseIdentityHash());
        knowledge.preparePublicationTarget(false, owner);
        store.checkpoint(id, "INDEXING", true, null, null);
        AtomicInteger calls = new AtomicInteger();
        long changedChunks = lifecycle.snapshot().chunks().stream()
                .filter(chunk -> sourceId.equals(chunk.getMetadata().get("source_id"))).count();
        int maximumCalls = Math.max(1, (int) (changedChunks + 9) / 10);
        knowledge.publicationBudget(() -> {
            // A user action has finite batches and no automatic retries; explicit recovery is separately audited.
            if (calls.incrementAndGet() > maximumCalls) throw new IllegalStateException("MANAGEMENT_REQUEST_BOUND_EXCEEDED");
            store.reserveEmbedding(id);
        });
        knowledge.syncManagedSource(sourceId, revoked);
        String fingerprint = knowledge.verifyPublicationIndex();
        KnowledgeCatalog verified = lifecycle.snapshot();
        store.checkpoint(id, "COMPLETED", true, lifecycle.authorityId(), verified.epoch(), fingerprint);
        knowledge.confirmPublication(verified);
        return new Result(id, "COMPLETED", verified.epoch());
    }

    private static void failUnlessCompleted(JdbcCatalogStore store, String id, RuntimeException failure) {
        try {
            if (!"COMPLETED".equals(store.operation(id).status())) store.failOperation(id, "MANAGEMENT_INDEX_RECOVERY_REQUIRED");
        } catch (RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
    }

    private void releasePublication() {
        try { knowledge.publicationBudget(() -> { }); }
        catch (RuntimeException ignored) { /* A lost owner remains unavailable. */ }
        finally { lifecycle.endPublication(); }
    }

    private void verifyIndex(JdbcCatalogStore.ReleaseReceipt anchor, JdbcCatalogStore.ReleaseReceipt effective) {
        try {
            String owner = KnowledgeCatalog.hash("chen-release-v1|" + anchor.operation().environment() + "|"
                    + anchor.operation().collection() + "|" + lifecycle.store().databaseIdentityHash());
            knowledge.preparePublicationTarget(false, owner);
            if (!Objects.equals(effective.indexHash(), knowledge.verifyPublicationIndex(
                    effective.operation().indexAuthority(), effective.operation().indexEpoch()))) {
                throw new IllegalStateException("MANAGEMENT_INDEX_DIGEST_MISMATCH");
            }
        } catch (RuntimeException failure) { knowledge.blockPublication(); throw failure; }
    }

    static void validateReplacement(KnowledgeDocument old, KnowledgeDocument replacement) {
        if (!old.sourceId().equals(replacement.sourceId()) || !old.sourceType().equals(replacement.sourceType())
                || !old.sourceUrl().equals(replacement.sourceUrl()) || !"PUBLIC".equals(replacement.visibility())
                || !"DOCUMENT_REVIEWED".equals(replacement.reviewStatus()) || old.version().equals(replacement.version())
                || replacement.updatedAt() == null || replacement.updatedAt().isBefore(old.updatedAt())) {
            throw new IllegalArgumentException("MANAGEMENT_REPLACEMENT_INVALID");
        }
        new KnowledgeCatalog(List.of(replacement));
    }

    /** 新增来源必须使用管理端专用前缀，并完整通过目录字段和页面关联校验。 */
    static void validateAddition(KnowledgeDocument addition) {
        if (!addition.sourceId().startsWith("help-managed-") || !"PLATFORM_HELP".equals(addition.sourceType())
                || !"PUBLIC".equals(addition.visibility()) || !"DOCUMENT_REVIEWED".equals(addition.reviewStatus())
                || addition.updatedAt() == null) {
            throw new IllegalArgumentException("MANAGEMENT_ADDITION_INVALID");
        }
        new KnowledgeCatalog(List.of(addition));
    }

    private static String requestHash(String sourceId, KnowledgeDocument replacement, long epoch, String hash, int actor) {
        try {
            Map<String, Object> content = replacement == null ? Map.of() : Map.of(
                    "title", replacement.title(), "text", replacement.text(), "version", replacement.version(),
                    "sourceType", replacement.sourceType(), "sourceUrl", replacement.sourceUrl(),
                    "visibility", replacement.visibility(), "reviewStatus", replacement.reviewStatus(),
                    "keywords", replacement.keywords() == null ? List.of() : replacement.keywords());
            return KnowledgeCatalog.hash(JSON.writeValueAsString(new Request(sourceId, content, epoch, hash, actor)));
        } catch (Exception invalid) { throw new IllegalArgumentException("MANAGEMENT_INPUT_INVALID"); }
    }
    private record Request(String source, Map<String, Object> content, long epoch, String previousHash, int actor) { }
}
