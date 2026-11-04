package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 单后端实例MySQL权威状态；默认不补种，首次导入仅由受控本地 bootstrap 执行。 */
public final class JdbcCatalogStore implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    record Row(KnowledgeDocument document, boolean tombstone, boolean deleted, long generation,
               Map<String, String> versionHashes) {
        Row { versionHashes = Collections.unmodifiableMap(new TreeMap<>(versionHashes)); }
    }
    record State(long generation, List<Row> rows) {
        State { rows = List.copyOf(rows); }
        List<KnowledgeDocument> documents() { return rows.stream().filter(row -> !row.deleted()).map(Row::document).toList(); }
    }
    private final DataSource source;
    private Connection connection;
    private final String lockName;
    private State state;
    private boolean closed;

    public static final class OwnerLost extends IllegalStateException {
        private OwnerLost(SQLException cause) { super("CATALOG_OWNER_LOST", cause); }
    }

    private JdbcCatalogStore(DataSource source) throws SQLException {
        this.source = source;
        connection = source.getConnection();
        lockName = "chen-rag-" + KnowledgeCatalog.hash(Objects.requireNonNull(connection.getCatalog())).substring(0, 40);
        try {
            acquireOwner(connection);
        } catch (Exception error) {
            connection.close();
            throw error;
        }
    }

    private void acquireOwner(Connection target) throws SQLException {
        target.setNetworkTimeout(Runnable::run, 3000);
        target.setAutoCommit(false);
        // A pooled physical session may already hold this exact lock; do not increment its reentrant count.
        if (ownsSessionLock(target)) return;
        try (PreparedStatement query = target.prepareStatement("SELECT GET_LOCK(?,0)")) {
            query.setQueryTimeout(3);
            query.setString(1, lockName);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new SQLException("CATALOG_OWNER_BUSY");
            }
        }
    }

    private boolean ownsSessionLock(Connection target) throws SQLException {
        try (PreparedStatement query = target.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            query.setQueryTimeout(3); query.setString(1, lockName);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("CATALOG_OWNER_STATE_UNAVAILABLE");
                return result.getBoolean(1);
            }
        }
    }

    public static JdbcCatalogStore open(DataSource source) {
        JdbcCatalogStore store = null;
        try {
            store = new JdbcCatalogStore(source);
            store.state = store.read();
            new KnowledgeCatalog(store.state.documents());
            store.connection.commit();
            return store;
        } catch (Exception error) {
            if (store != null) { store.close(); }
            throw new IllegalStateException("CATALOG_RECOVERY_FAILED", error);
        }
    }

    /** Wiki readers share the committed authority without becoming a second catalog writer. */
    static State readOnlySnapshot(DataSource source) {
        try (Connection connection = source.getConnection()) {
            connection.setNetworkTimeout(Runnable::run, 3000);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                State recovered = readState(connection, false);
                new KnowledgeCatalog(recovered.documents());
                return recovered;
            } finally {
                connection.rollback();
            }
        } catch (Exception error) {
            throw new IllegalStateException("CATALOG_READ_ONLY_RECOVERY_FAILED", error);
        }
    }

    /** 仅显式初始化空的专用表；不创建表，不覆盖既有状态，不发布来源。 */
    public static void initialize(DataSource source, List<KnowledgeDocument> documents) {
        initializeApproved(source, documents, null);
    }

    static void initializeApproved(DataSource source, List<KnowledgeDocument> documents, ReleaseOperation operation) {
        new KnowledgeCatalog(documents);
        try (JdbcCatalogStore store = new JdbcCatalogStore(source)) {
            try (Statement query = store.connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT (SELECT COUNT(*) FROM rag_catalog_state)+(SELECT COUNT(*) FROM rag_source_state)")) {
                if (!result.next() || result.getLong(1) != 0) { throw new IllegalStateException("CATALOG_ALREADY_INITIALIZED"); }
            }
            List<Row> rows = documents.stream().sorted(Comparator.comparing(KnowledgeDocument::sourceId))
                    .map(document -> new Row(document, "REVOKED".equals(document.reviewStatus()), false, 0,
                            Map.of(document.version(), contentHash(document)))).toList();
            State initial = new State(0, rows);
            try (PreparedStatement insert = store.connection.prepareStatement(
                    "INSERT INTO rag_catalog_state(id,generation,snapshot_sha256,schema_version) VALUES(1,0,?,1)")) {
                insert.setString(1, digest(initial)); insert.executeUpdate();
            }
            store.writeRows(initial);
            if (operation != null) {
                if (documents.stream().anyMatch(d -> !"NOT_PUBLISHED".equals(d.visibility()) || !"CONTENT_REVIEW_REQUIRED".equals(d.reviewStatus()))) {
                    throw new IllegalStateException("RELEASE_BOOTSTRAP_REQUIRES_DRAFTS");
                }
                store.state = initial;
                store.insertOperation(operation);
                store.appendEvent(operation.id(), "BOOTSTRAP", 0, "APPROVED_EMPTY_CATALOG_INITIALIZED");
            }
            store.connection.commit();
        } catch (Exception error) {
            throw new IllegalStateException("CATALOG_INITIALIZATION_FAILED", error);
        }
    }

    synchronized State recovered() { return state; }

    synchronized String snapshotHash() {
        try { return digest(state); }
        catch (Exception error) { throw new IllegalStateException("CATALOG_CHECKSUM_FAILED", error); }
    }

    /** Bind an index to this endpoint/schema without retaining credentials or connection options. */
    synchronized String databaseIdentityHash() {
        requireOwner();
        try {
            String jdbc = connection.getMetaData().getURL();
            if (!jdbc.startsWith("jdbc:mysql://")) throw new IllegalStateException("RELEASE_DATABASE_IDENTITY_INVALID");
            java.net.URI address = java.net.URI.create(jdbc.substring("jdbc:".length()));
            if (address.getHost() == null || connection.getCatalog() == null) throw new IllegalStateException("RELEASE_DATABASE_IDENTITY_INVALID");
            return KnowledgeCatalog.hash("mysql://" + address.getHost().toLowerCase(Locale.ROOT) + ":"
                    + (address.getPort() < 0 ? 3306 : address.getPort()) + "/" + connection.getCatalog());
        } catch (Exception error) { throw new IllegalStateException("RELEASE_DATABASE_IDENTITY_UNAVAILABLE"); }
    }

    synchronized void requireOwner() {
        try {
            if (!ownsSessionLock(connection)) throw new SQLException("CATALOG_OWNER_LOST");
        } catch (SQLException error) { throw new OwnerLost(error); }
    }

    /** Reconnection only restores the same committed authority; callers must reverify its index before serving reads. */
    synchronized void reconnectUnchanged() {
        if (closed) throw new IllegalStateException("CATALOG_STORE_CLOSED");
        disconnectOwner();
        Connection replacement = null;
        try {
            replacement = source.getConnection();
            acquireOwner(replacement);
            State recovered = readState(replacement, true);
            if (state.generation() != recovered.generation() || !digest(state).equals(digest(recovered))) {
                throw new IllegalStateException("CATALOG_RECOVERY_STATE_CHANGED");
            }
            replacement.commit();
            connection = replacement;
        } catch (Exception error) {
            if (replacement != null) releaseOwner(replacement, false);
            throw new IllegalStateException("CATALOG_OWNER_RECONNECT_REJECTED", error);
        }
    }

    synchronized void disconnectOwner() { releaseOwner(connection, false); }

    private void releaseOwner(Connection target, boolean reportClose) {
        boolean discard = false;
        try { target.rollback(); } catch (SQLException ignored) { discard = true; }
        try (PreparedStatement release = target.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            release.setQueryTimeout(3);
            release.setString(1, lockName); release.execute();
            discard |= ownsSessionLock(target);
        } catch (SQLException ignored) { discard = true; }
        // Proxy close only returns a pooled lease; an uncertain or reentrant lock needs physical termination.
        SQLException abortFailure = null;
        if (discard) {
            try { target.abort(Runnable::run); }
            catch (SQLException error) { abortFailure = error; }
        }
        try { target.close(); }
        catch (SQLException error) {
            if (abortFailure != null) error.addSuppressed(abortFailure);
            // Hikari may fail to reset a physically aborted session while still returning its lease.
            if (reportClose && !(discard && abortFailure == null)) throw new IllegalStateException("CATALOG_STORE_CLOSE_FAILED", error);
        }
        if (reportClose && abortFailure != null) throw new IllegalStateException("CATALOG_STORE_ABORT_FAILED", abortFailure);
    }

    synchronized void commit(String id, String event, KnowledgeCatalog next, long expectedGeneration) {
        commit(id == null ? List.of() : List.of(id), event, next, expectedGeneration, null);
    }

    synchronized void commit(List<String> ids, String event, KnowledgeCatalog next, long expectedGeneration, String operationId) {
        try {
            requireOwner();
            State persisted = read();
            if (persisted.generation() != expectedGeneration || !digest(persisted).equals(digest(state))) {
                throw new IllegalStateException("CATALOG_GENERATION_CONFLICT");
            }
            Map<String, Row> rows = new TreeMap<>();
            state.rows().forEach(row -> rows.put(row.document().sourceId(), row));
            for (String id : ids) {
                Row old = rows.get(id);
                if (old != null && old.tombstone() && (event.equals("ADD") || event.equals("UPDATE") || event.equals("PUBLISH"))) {
                    throw new IllegalStateException("CATALOG_TOMBSTONE_PERMANENT");
                }
                KnowledgeDocument document = next.documents().stream().filter(item -> item.sourceId().equals(id)).findFirst().orElse(null);
                boolean deleted = event.equals("DELETE");
                if (deleted) {
                    if (old == null) { throw new IllegalStateException("CATALOG_SOURCE_MISSING"); }
                    KnowledgeDocument d = old.document();
                    document = new KnowledgeDocument(d.sourceId(), d.title(), d.text(), d.version(), d.sourceType(),
                            d.sourceUrl(), d.visibility(), "REVOKED", d.updatedAt(), d.keywords());
                }
                if (document == null) { throw new IllegalStateException("CATALOG_SOURCE_MISSING"); }
                Map<String, String> versions = new TreeMap<>(old == null ? Map.of() : old.versionHashes());
                if (versions.containsKey(document.version()) && (!versions.get(document.version()).equals(contentHash(document))
                        || (old != null && !old.document().version().equals(document.version())))) {
                    throw new IllegalStateException("CATALOG_VERSION_REPLAY");
                }
                versions.put(document.version(), contentHash(document));
                rows.put(id, new Row(document, deleted || "REVOKED".equals(document.reviewStatus()), deleted, next.epoch(), versions));
            }
            State replacement = new State(next.epoch(), List.copyOf(rows.values()));
            writeRows(replacement);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE rag_catalog_state SET generation=?,snapshot_sha256=? WHERE id=1 AND generation=?")) {
                update.setLong(1, replacement.generation()); update.setString(2, digest(replacement)); update.setLong(3, expectedGeneration);
                if (update.executeUpdate() != 1) { throw new SQLException("CATALOG_GENERATION_CONFLICT"); }
            }
            if (operationId != null) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE rag_release_operation SET status='CATALOG_APPLIED',applied_generation=?,applied_snapshot_sha256=?,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND status='VALIDATED'")) {
                    update.setLong(1, replacement.generation()); update.setString(2, digest(replacement)); update.setString(3, operationId);
                    if (update.executeUpdate() != 1) throw new SQLException("RELEASE_STATE_CONFLICT");
                }
                appendEvent(operationId, "CATALOG_APPLIED", replacement.generation(), event);
            }
            connection.commit();
            state = replacement;
        } catch (Exception error) {
            try { connection.rollback(); } catch (SQLException ignored) { /* 提交结果不明，由生命周期统一失败关闭。 */ }
            throw new IllegalStateException("CATALOG_COMMIT_FAILED_CLOSED", error);
        }
    }

    // 兼容旧schema：approval_sha256存操作记录原始Hash，approver_id存授权模式，均不是签名证明。
    record ReleaseOperation(String id, String manifestHash, String operationHash, String environment,
            String operator, String authorizationMode, String type, String status, long expectedGeneration,
            Long appliedGeneration, String appliedSnapshotHash, String collection, long maxEmbeddingRequests,
            long embeddingRequests, boolean targetInitialized, String indexAuthority, Long indexEpoch, String error) {}

    record ReleaseReceipt(ReleaseOperation operation, java.time.Instant validatedAt,
                          java.time.Instant completedAt, String indexHash) { }

    /** 新事务只读重验持久快照；不能用连接内缓存替代恢复证据。 */
    synchronized void requireUnchanged() {
        try {
            connection.rollback();
            State persisted = read();
            if (persisted.generation() != state.generation() || !digest(persisted).equals(digest(state))) {
                throw new IllegalStateException("CATALOG_GENERATION_CONFLICT");
            }
        } catch (Exception error) { throw new IllegalStateException("CATALOG_RECOVERY_STATE_DRIFT", error); }
        finally { rollback(); }
    }

    synchronized ReleaseReceipt completedReceipt(String manifestHash) {
        requireUnchanged();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT operation_id FROM rag_release_operation WHERE manifest_sha256=? AND approver_id='CONTROLLED_OPERATOR' AND status='COMPLETED'")) {
            query.setString(1, manifestHash);
            String id;
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("RECOVERY_COMPLETED_RECEIPT_REQUIRED");
                id = result.getString(1);
                if (result.next()) throw new IllegalStateException("RECOVERY_RECEIPT_AMBIGUOUS");
            }
            return completedOperationReceipt(id);
        } catch (SQLException error) { throw new IllegalStateException("RECOVERY_RECEIPT_READ_FAILED", error); }
        finally { rollback(); }
    }

    private synchronized ReleaseReceipt completedOperationReceipt(String id) {
        requireUnchanged();
        try {
            ReleaseOperation operation = operation(id);
            if (operation == null || !"CONTROLLED_OPERATOR".equals(operation.authorizationMode()) || !"COMPLETED".equals(operation.status())) {
                throw new IllegalStateException("RECOVERY_COMPLETED_RECEIPT_REQUIRED");
            }
            java.time.Instant validated = null, completed = null;
            String hash = null;
            try (PreparedStatement events = connection.prepareStatement(
                    "SELECT event_type,TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',created_at),details_code FROM rag_release_event WHERE operation_id=? ORDER BY id")) {
                events.setString(1, id);
                try (ResultSet result = events.executeQuery()) {
                    while (result.next()) {
                        long micros = result.getLong(2);
                        java.time.Instant time = java.time.Instant.ofEpochSecond(micros / 1_000_000, micros % 1_000_000 * 1000);
                        if ("VALIDATED".equals(result.getString(1)) && validated == null) {
                            if (!"LOCAL_OPERATOR_AND_SCOPE_VERIFIED".equals(result.getString(3))) {
                                throw new IllegalStateException("RECOVERY_CONTROLLED_OPERATION_REQUIRED");
                            }
                            validated = time;
                        }
                        if ("COMPLETED".equals(result.getString(1))) {
                            completed = time;
                            String code = result.getString(3);
                            hash = code != null && code.startsWith("INDEX_SHA256:") ? code.substring("INDEX_SHA256:".length()) : null;
                        }
                    }
                }
            }
            if (validated == null || completed == null || completed.isBefore(validated) || !PublicationManifest.validHash(hash)) {
                throw new IllegalStateException("RECOVERY_VERIFIABLE_INDEX_RECEIPT_REQUIRED");
            }
            return new ReleaseReceipt(operation, validated, completed, hash);
        } catch (SQLException error) { throw new IllegalStateException("RECOVERY_RECEIPT_READ_FAILED", error); }
        finally { rollback(); }
    }

    synchronized ReleaseReceipt latestCompletedReceipt(String environment, String collection) {
        requireUnchanged();
        String operationId;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT op.operation_id FROM rag_release_operation op
                JOIN rag_release_event ev ON ev.operation_id=op.operation_id AND ev.event_type='COMPLETED'
                WHERE op.status='COMPLETED' AND op.approver_id='CONTROLLED_OPERATOR'
                  AND op.environment=? AND op.target_collection=?
                  AND op.applied_generation=? AND op.applied_snapshot_sha256=?
                ORDER BY ev.id DESC LIMIT 1
                """)) {
            query.setString(1, environment); query.setString(2, collection);
            query.setLong(3, state.generation()); query.setString(4, snapshotHash());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("RECOVERY_COMPLETED_RECEIPT_REQUIRED");
                operationId = result.getString(1);
            }
        } catch (SQLException error) { throw new IllegalStateException("RECOVERY_RECEIPT_READ_FAILED", error); }
        finally { rollback(); }
        ReleaseReceipt receipt = completedOperationReceipt(operationId);
        ReleaseOperation operation = receipt.operation();
        if (!environment.equals(operation.environment()) || !collection.equals(operation.collection())
                || !Objects.equals(state.generation(), operation.appliedGeneration())
                || !snapshotHash().equals(operation.appliedSnapshotHash())) throw new IllegalStateException("RECOVERY_RECEIPT_STATE_MISMATCH");
        return receipt;
    }

    synchronized ReleaseOperation operation(String id) {
        requireOwner();
        try (PreparedStatement query = connection.prepareStatement("SELECT operation_id,manifest_sha256,approval_sha256,environment,operator_id,approver_id,operation_type,status,expected_generation,applied_generation,applied_snapshot_sha256,target_collection,max_embedding_requests,embedding_requests,target_initialized,index_authority,index_epoch,last_error_code FROM rag_release_operation WHERE operation_id=?")) {
            query.setString(1, id);
            try (ResultSet r = query.executeQuery()) {
                if (!r.next()) return null;
                return new ReleaseOperation(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getLong(9),
                        r.getObject(10) == null ? null : r.getLong(10), r.getString(11), r.getString(12),
                        r.getLong(13), r.getLong(14), r.getBoolean(15), r.getString(16),
                        r.getObject(17) == null ? null : r.getLong(17), r.getString(18));
            }
        } catch (SQLException error) { throw new IllegalStateException("RELEASE_READ_FAILED", error); }
    }

    synchronized ReleaseOperation prepareOperation(ReleaseOperation requested) {
        try {
            requireOwner();
            ReleaseOperation previous = operation(requested.id());
            if (previous != null) {
                if (!previous.manifestHash().equals(requested.manifestHash()) || !previous.operationHash().equals(requested.operationHash())
                        || !previous.environment().equals(requested.environment()) || !previous.operator().equals(requested.operator())
                        || !previous.authorizationMode().equals(requested.authorizationMode()) || !previous.type().equals(requested.type())
                        || previous.expectedGeneration() != requested.expectedGeneration()
                        || !previous.collection().equals(requested.collection())
                        || previous.maxEmbeddingRequests() != requested.maxEmbeddingRequests()) {
                    throw new IllegalStateException("RELEASE_OPERATION_REPLAY");
                }
                connection.commit();
                return previous;
            }
            insertOperation(requested);
            connection.commit();
            return operation(requested.id());
        } catch (Exception error) {
            rollback();
            throw new IllegalStateException("RELEASE_PREPARE_FAILED_CLOSED", error);
        }
    }

    private void insertOperation(ReleaseOperation requested) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO rag_release_operation(operation_id,manifest_sha256,approval_sha256,environment,operator_id,approver_id,operation_type,status,expected_generation,target_collection,max_embedding_requests,embedding_requests,target_initialized,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'VALIDATED',?,?,?,0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")) {
            insert.setString(1, requested.id()); insert.setString(2, requested.manifestHash()); insert.setString(3, requested.operationHash());
            insert.setString(4, requested.environment()); insert.setString(5, requested.operator()); insert.setString(6, requested.authorizationMode());
            insert.setString(7, requested.type()); insert.setLong(8, requested.expectedGeneration());
            insert.setString(9, requested.collection()); insert.setLong(10, requested.maxEmbeddingRequests()); insert.executeUpdate();
        }
        appendEvent(requested.id(), "VALIDATED", state.generation(),
                "AUTHENTICATED_KNOWLEDGE_ADMIN".equals(requested.authorizationMode())
                        ? "AUTHENTICATED_PERMISSION_AND_SCOPE_VERIFIED" : "LOCAL_OPERATOR_AND_SCOPE_VERIFIED");
    }

    synchronized void bindManagement(String id, String previousHash, String sourceId) {
        try {
            requireOwner();
            String previous = eventDetail(id, "PREVIOUS_SNAPSHOT");
            String source = eventDetail(id, "MANAGED_SOURCE");
            if (previous == null && source == null) {
                appendEvent(id, "PREVIOUS_SNAPSHOT", state.generation(), previousHash);
                appendEvent(id, "MANAGED_SOURCE", state.generation(), sourceId);
            } else if (!previousHash.equals(previous) || !sourceId.equals(source)) {
                throw new IllegalStateException("MANAGEMENT_OPERATION_REPLAY");
            }
            connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("MANAGEMENT_BINDING_FAILED", error); }
    }

    private String eventDetail(String id, String type) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT details_code FROM rag_release_event WHERE operation_id=? AND event_type=? ORDER BY id")) {
            query.setString(1, id); query.setString(2, type);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                String value = result.getString(1);
                if (result.next()) throw new IllegalStateException("MANAGEMENT_EVENT_AMBIGUOUS");
                return value;
            }
        }
    }

    synchronized void resumeUnappliedManagement(String id) {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rag_release_operation SET status='VALIDATED',last_error_code=NULL
                WHERE operation_id=? AND approver_id='AUTHENTICATED_KNOWLEDGE_ADMIN'
                  AND status='FAILED' AND applied_generation IS NULL
                """)) {
            requireOwner(); update.setString(1, id);
            if (update.executeUpdate() > 0) appendEvent(id, "RETRY_REQUESTED", state.generation(), "EXPLICIT_AUTHENTICATED_RETRY");
            connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("MANAGEMENT_RETRY_FAILED", error); }
    }

    synchronized void inheritManagedContent(String id, ReleaseReceipt previous) {
        try {
            requireOwner();
            String hash = previous.operation().manifestHash();
            String existing = eventDetail(id, "INHERITED_CONTENT");
            if (existing == null) appendEvent(id, "INHERITED_CONTENT", state.generation(), hash);
            else if (!existing.equals(hash)) throw new IllegalStateException("RECOVERY_CONTENT_ORIGIN_REPLAY");
            connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("RECOVERY_CONTENT_ORIGIN_FAILED", error); }
    }

    synchronized String managedSource(String id) {
        requireOwner();
        try { return eventDetail(id, "MANAGED_SOURCE"); }
        catch (SQLException error) { throw new IllegalStateException("MANAGEMENT_SOURCE_READ_FAILED", error); }
        finally { rollback(); }
    }

    /** 只读查询后释放当前连接上的事务状态，不改变已提交快照。 */
    synchronized void rollbackQuietly() { rollback(); }

    /**
     * 已由认证管理员新增且完成索引核验的来源。发布清单只覆盖原始40个主题，
     * 正常重启仍需接受这些新增来源，但不接受任意数据库行或未完成操作。
     */
    synchronized Set<String> createdSources(String environment, String collection) {
        requireOwner();
        try {
            Set<String> result = new LinkedHashSet<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT o.operation_id FROM rag_release_operation o
                    JOIN rag_release_event ev ON ev.operation_id=o.operation_id AND ev.event_type='COMPLETED'
                    WHERE o.approver_id='AUTHENTICATED_KNOWLEDGE_ADMIN' AND o.operation_type='KNOWLEDGE_CREATE'
                      AND o.status='COMPLETED' AND o.applied_generation IS NOT NULL
                      AND o.index_authority IS NOT NULL AND o.index_epoch=o.applied_generation
                      AND o.environment=? AND o.target_collection=?
                      AND ev.details_code LIKE 'INDEX_SHA256:%'
                    ORDER BY o.applied_generation,o.operation_id
                    """)) {
                query.setString(1, environment);
                query.setString(2, collection);
                List<String> ids = new ArrayList<>();
                try (ResultSet resultSet = query.executeQuery()) { while (resultSet.next()) ids.add(resultSet.getString(1)); }
                for (String id : ids) {
                    String source = eventDetail(id, "MANAGED_SOURCE");
                    if (source == null || !source.matches("help-managed-[a-z0-9-]{1,64}")) continue;
                    if (state.rows().stream().anyMatch(row -> row.document().sourceId().equals(source))) result.add(source);
                }
            }
            return Set.copyOf(result);
        } catch (SQLException error) {
            throw new IllegalStateException("MANAGEMENT_SOURCE_READ_FAILED", error);
        } finally { rollback(); }
    }

    synchronized void recordManagementRecovery(String id, int actorId) {
        try {
            requireOwner();
            ReleaseOperation operation = operation(id);
            if (actorId <= 0 || operation == null || !"AUTHENTICATED_KNOWLEDGE_ADMIN".equals(operation.authorizationMode())
                    || operation.appliedGeneration() == null) throw new IllegalArgumentException("MANAGEMENT_RECOVERY_INVALID");
            appendEvent(id, "RECOVERY_REQUESTED", state.generation(), "user:" + actorId);
            connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("MANAGEMENT_RECOVERY_AUDIT_FAILED", error); }
    }

    record ManagementChain(ReleaseReceipt effective, Set<String> changedSources, boolean complete) {
        ManagementChain { changedSources = Set.copyOf(changedSources); }
    }

    synchronized boolean hasManagedChanges() {
        requireOwner();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rag_release_operation
                WHERE approver_id='AUTHENTICATED_KNOWLEDGE_ADMIN' AND applied_generation IS NOT NULL
                """); ResultSet result = query.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("MANAGEMENT_HISTORY_UNAVAILABLE");
            return result.getLong(1) > 0;
        } catch (SQLException error) { throw new IllegalStateException("MANAGEMENT_HISTORY_UNAVAILABLE", error); }
        finally { rollback(); }
    }

    synchronized ReleaseReceipt latestControlledReceipt(String collection) {
        requireUnchanged();
        String operationId;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT op.operation_id FROM rag_release_operation op
                JOIN rag_release_event ev ON ev.operation_id=op.operation_id AND ev.event_type='COMPLETED'
                WHERE op.status='COMPLETED' AND op.approver_id='CONTROLLED_OPERATOR' AND op.target_collection=?
                ORDER BY ev.id DESC LIMIT 1
                """)) {
            query.setString(1, collection);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("RECOVERY_COMPLETED_RECEIPT_REQUIRED");
                operationId = result.getString(1);
            }
        } catch (SQLException error) { throw new IllegalStateException("RECOVERY_RECEIPT_READ_FAILED", error); }
        finally { rollback(); }
        ReleaseReceipt receipt = completedOperationReceipt(operationId);
        if (!collection.equals(receipt.operation().collection())) throw new IllegalStateException("RECOVERY_RECEIPT_STATE_MISMATCH");
        return receipt;
    }

    /** Management extends an existing controlled receipt; it never impersonates its authorization. */
    synchronized ManagementChain managementChain(ReleaseReceipt anchor) {
        requireUnchanged();
        try {
            List<ReleaseReceipt> anchors = new ArrayList<>();
            Set<String> lineage = new LinkedHashSet<>();
            ReleaseReceipt cursor = anchor;
            while (true) {
                ReleaseOperation entry = cursor.operation();
                if (!lineage.add(entry.manifestHash()) || lineage.size() > 64) throw new IllegalStateException("MANAGEMENT_ORIGIN_INVALID");
                if (entry.appliedGeneration() == null || entry.appliedGeneration() > state.generation()
                        || !Set.of("PUBLISH", "UPDATE", "RECOVER").contains(entry.type())
                        || entry.appliedGeneration() != entry.expectedGeneration() + ("RECOVER".equals(entry.type()) ? 0 : 1)
                        || !Objects.equals(entry.indexEpoch(), entry.appliedGeneration())
                        || !entry.targetInitialized() || !PublicationManifest.validHash(entry.appliedSnapshotHash())
                        || !PublicationManifest.validHash(entry.operationHash())) throw new IllegalStateException("MANAGEMENT_ORIGIN_INVALID");
                anchors.add(cursor);
                String parent = eventDetail(entry.id(), "INHERITED_CONTENT");
                if (parent == null) break;
                ReleaseReceipt previous = completedReceipt(parent);
                if (!entry.environment().equals(previous.operation().environment())
                        || !entry.collection().equals(previous.operation().collection())
                        || !entry.operator().equals(previous.operation().operator())
                        || !"RECOVER".equals(entry.type())
                        || previous.operation().appliedGeneration() > entry.appliedGeneration()
                        || previous.completedAt().isAfter(cursor.completedAt())) throw new IllegalStateException("MANAGEMENT_ORIGIN_INVALID");
                cursor = previous;
            }
            ReleaseOperation base = cursor.operation();
            long generation = Objects.requireNonNull(base.appliedGeneration());
            String snapshot = base.appliedSnapshotHash();
            ReleaseReceipt effective = cursor;
            Set<String> sources = new LinkedHashSet<>();
            String placeholders = String.join(",", java.util.Collections.nCopies(lineage.size(), "?"));
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT operation_id FROM rag_release_operation
                    WHERE approver_id='AUTHENTICATED_KNOWLEDGE_ADMIN' AND manifest_sha256 IN (
                    """ + placeholders + ") AND applied_generation>? ORDER BY applied_generation,operation_id")) {
                int parameter = 1;
                for (String hash : lineage) query.setString(parameter++, hash);
                query.setLong(parameter, generation);
                List<String> ids = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) { while (result.next()) ids.add(result.getString(1)); }
                for (String id : ids) {
                    ReleaseOperation operation = operation(id);
                    String source = eventDetail(id, "MANAGED_SOURCE");
                    boolean knownSource = source != null && (PublicationManifest.userSourceIds().contains(source)
                            || state.rows().stream().anyMatch(row -> row.document().sourceId().equals(source)));
                    if (!operation.environment().equals(base.environment()) || !operation.collection().equals(base.collection())
                            || !operation.operator().matches("user:[1-9][0-9]*")
                            || !Set.of("KNOWLEDGE_CREATE", "KNOWLEDGE_UPDATE", "KNOWLEDGE_REVOKE").contains(operation.type())
                            || !PublicationManifest.validHash(operation.operationHash())
                            || operation.embeddingRequests() < 0 || operation.embeddingRequests() > operation.maxEmbeddingRequests()
                            || operation.expectedGeneration() != generation || operation.appliedGeneration() != generation + 1
                            || !snapshot.equals(eventDetail(id, "PREVIOUS_SNAPSHOT"))
                            || !PublicationManifest.validHash(operation.appliedSnapshotHash())
                            || !knownSource
                            || !"AUTHENTICATED_PERMISSION_AND_SCOPE_VERIFIED".equals(eventDetail(id, "VALIDATED"))) {
                        throw new IllegalStateException("MANAGEMENT_CHAIN_INVALID");
                    }
                    generation = operation.appliedGeneration(); snapshot = operation.appliedSnapshotHash(); sources.add(source);
                    for (ReleaseReceipt checkpoint : anchors) {
                        if (checkpoint.operation().appliedGeneration() == generation
                                && !snapshot.equals(checkpoint.operation().appliedSnapshotHash())) throw new IllegalStateException("MANAGEMENT_ORIGIN_STATE_DRIFT");
                    }
                    boolean completed = "COMPLETED".equals(operation.status());
                    String code = completed ? eventDetail(id, "COMPLETED") : null;
                    String hash = code != null && code.startsWith("INDEX_SHA256:") ? code.substring(13) : null;
                    if (completed && (!PublicationManifest.validHash(hash) || !operation.targetInitialized()
                            || operation.indexAuthority() == null || !operation.indexAuthority().matches("[0-9a-f-]{36}")
                            || !Objects.equals(operation.indexEpoch(), generation))) throw new IllegalStateException("MANAGEMENT_RECEIPT_INVALID");
                    if (!completed && (generation != state.generation()
                            || !Set.of("FAILED", "CATALOG_APPLIED", "INDEXING").contains(operation.status()))) {
                        throw new IllegalStateException("MANAGEMENT_INCOMPLETE_CHAIN");
                    }
                    effective = new ReleaseReceipt(operation, anchor.validatedAt(), anchor.completedAt(), hash);
                }
            }
            if (generation != state.generation() || !snapshot.equals(snapshotHash())) throw new IllegalStateException("MANAGEMENT_CHAIN_STATE_DRIFT");
            if (anchor.operation().appliedGeneration() == generation) effective = anchor;
            return new ManagementChain(effective, sources, "COMPLETED".equals(effective.operation().status()));
        } catch (SQLException error) { throw new IllegalStateException("MANAGEMENT_CHAIN_READ_FAILED", error); }
        finally { rollback(); }
    }

    synchronized void checkpoint(String id, String status, boolean initialized, String authority, Long epoch) {
        checkpoint(id, status, initialized, authority, epoch, null);
    }

    synchronized void checkpoint(String id, String status, boolean initialized, String authority, Long epoch, String indexHash) {
        try {
            requireOwner();
            State persisted = read();
            if (persisted.generation() != state.generation() || !digest(persisted).equals(digest(state))) {
                throw new IllegalStateException("CATALOG_GENERATION_CONFLICT");
            }
            if ("COMPLETED".equals(status)) {
                if (!PublicationManifest.validHash(indexHash)) throw new IllegalStateException("RELEASE_INDEX_DIGEST_REQUIRED");
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE rag_release_operation SET applied_generation=?,applied_snapshot_sha256=? WHERE operation_id=?")) {
                    update.setLong(1, state.generation()); update.setString(2, digest(state)); update.setString(3, id);
                    if (update.executeUpdate() != 1) throw new SQLException("RELEASE_STATE_MISSING");
                }
            }
            try (PreparedStatement update = connection.prepareStatement("UPDATE rag_release_operation SET status=?,target_initialized=?,index_authority=?,index_epoch=?,last_error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?")) {
                update.setString(1, status); update.setBoolean(2, initialized); update.setString(3, authority);
                if (epoch == null) update.setNull(4, Types.BIGINT); else update.setLong(4, epoch);
                update.setString(5, id);
                if (update.executeUpdate() != 1) throw new SQLException("RELEASE_STATE_MISSING");
            }
            appendEvent(id, status, state.generation(), indexHash == null ? "INDEX_AUTHORITY_BOUND" : "INDEX_SHA256:" + indexHash); connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("RELEASE_CHECKPOINT_FAILED_CLOSED", error); }
    }

    synchronized void reserveEmbedding(String id) {
        try {
            requireOwner();
            try (PreparedStatement update = connection.prepareStatement("UPDATE rag_release_operation SET embedding_requests=embedding_requests+1,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=? AND status='INDEXING' AND embedding_requests<max_embedding_requests")) {
                update.setString(1, id);
                if (update.executeUpdate() != 1) throw new IllegalStateException("RELEASE_EMBEDDING_BUDGET_EXHAUSTED");
            }
            appendEvent(id, "EMBEDDING_RESERVED", state.generation(), "ATTEMPT_RESERVED_BEFORE_SEND"); connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("RELEASE_COST_RESERVATION_FAILED_CLOSED", error); }
    }

    synchronized void failOperation(String id, String safeCode) {
        try {
            requireOwner();
            State persisted = read();
            try (PreparedStatement update = connection.prepareStatement("UPDATE rag_release_operation SET status='FAILED',last_error_code=?,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id=?")) {
                update.setString(1, safeCode); update.setString(2, id); update.executeUpdate();
            }
            appendEvent(id, "FAILED", persisted.generation(), safeCode); connection.commit();
        } catch (Exception error) { rollback(); throw new IllegalStateException("RELEASE_FAILURE_RECORD_UNCONFIRMED", error); }
    }

    private void appendEvent(String id, String event, long generation, String code) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO rag_release_event(operation_id,event_type,generation,details_code,created_at) VALUES(?,?,?,?,UTC_TIMESTAMP(6))")) {
            insert.setString(1, id); insert.setString(2, event); insert.setLong(3, generation); insert.setString(4, code); insert.executeUpdate();
        }
    }

    private void rollback() {
        try { connection.rollback(); } catch (SQLException ignored) { /* Unknown outcome is never reported as success. */ }
    }

    private State read() throws Exception {
        requireOwner();
        return readState(connection, true);
    }

    private static State readState(Connection connection, boolean lockState) throws Exception {
        long generation; String expected;
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT id,generation,snapshot_sha256,schema_version FROM rag_catalog_state"
                     + (lockState ? " FOR UPDATE" : ""))) {
            if (!result.next() || result.getInt(1) != 1 || result.getInt(4) != 1 || result.getLong(2) < 0) {
                throw new SQLException("CATALOG_STATE_MISSING_OR_INVALID");
            }
            generation = result.getLong(2); expected = result.getString(3);
            if (result.next()) { throw new SQLException("CATALOG_STATE_DUPLICATE"); }
        }
        List<Row> rows = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history FROM rag_source_state ORDER BY source_id")) {
            while (result.next()) {
                KnowledgeDocument document = JSON.readValue(result.getString(3), KnowledgeDocument.class);
                Map<String, String> versions = JSON.readValue(result.getString(8), JSON.getTypeFactory().constructMapType(TreeMap.class,String.class,String.class));
                Row row = new Row(document, result.getBoolean(6), result.getBoolean(7), result.getLong(5), versions);
                if (!document.sourceId().equals(result.getString(1)) || !document.version().equals(result.getString(2))
                        || !contentHash(document).equals(result.getString(4)) || row.generation() < 0 || row.generation() > generation
                        || !contentHash(document).equals(versions.get(document.version())) || versions.isEmpty()
                        || versions.entrySet().stream().anyMatch(entry -> !entry.getKey().matches("[a-zA-Z0-9._-]{1,40}") || !entry.getValue().matches("[a-f0-9]{64}"))
                        || row.tombstone() != "REVOKED".equals(document.reviewStatus()) || row.deleted() && !row.tombstone()) {
                    throw new SQLException("CATALOG_SOURCE_CORRUPT");
                }
                rows.add(row);
            }
        }
        State recovered = new State(generation, rows);
        if (!digest(recovered).equals(expected)) { throw new SQLException("CATALOG_CHECKSUM_MISMATCH"); }
        return recovered;
    }

    private void writeRows(State next) throws Exception {
        try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO rag_source_state(source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history) VALUES(?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE version=VALUES(version),document_json=VALUES(document_json),content_sha256=VALUES(content_sha256),generation=VALUES(generation),tombstone=VALUES(tombstone),deleted=VALUES(deleted),version_history=VALUES(version_history)")) {
            for (Row row : next.rows()) {
                KnowledgeDocument d = row.document();
                upsert.setString(1, d.sourceId()); upsert.setString(2, d.version());
                // MySQL JSON会把小数时间戳存为DOUBLE；使用ISO文本保留Instant纳秒及快照校验和。
                upsert.setString(3, JSON.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(d));
                upsert.setString(4, contentHash(d)); upsert.setLong(5, row.generation()); upsert.setBoolean(6, row.tombstone());
                upsert.setBoolean(7, row.deleted()); upsert.setString(8, JSON.writeValueAsString(row.versionHashes())); upsert.addBatch();
            }
            upsert.executeBatch();
        }
    }

    private static String contentHash(KnowledgeDocument d) { return KnowledgeCatalog.hash(KnowledgeCatalog.normalize(d.text())); }
    private static String digest(State state) throws Exception { return KnowledgeCatalog.hash(JSON.writeValueAsString(state)); }

    @Override public synchronized void close() {
        closed = true;
        releaseOwner(connection, true);
    }
}
