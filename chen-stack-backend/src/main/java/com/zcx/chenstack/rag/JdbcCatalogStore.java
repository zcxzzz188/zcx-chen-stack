package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 单后端实例MySQL权威状态；建表和首次导入均由独立受控宿主执行，启动绝不补种。 */
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
    private final Connection connection;
    private final String lockName;
    private State state;

    private JdbcCatalogStore(DataSource source) throws SQLException {
        connection = source.getConnection();
        lockName = "chen-rag-" + KnowledgeCatalog.hash(Objects.requireNonNull(connection.getCatalog())).substring(0, 40);
        try {
            connection.setNetworkTimeout(Runnable::run, 3000);
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                query.setString(1, lockName);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) { throw new SQLException("CATALOG_OWNER_BUSY"); }
                }
            }
        } catch (Exception error) {
            connection.close();
            throw error;
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

    /** 仅显式初始化空的专用表；不创建表，不覆盖既有状态，不发布来源。 */
    public static void initialize(DataSource source, List<KnowledgeDocument> documents) {
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
            store.connection.commit();
        } catch (Exception error) {
            throw new IllegalStateException("CATALOG_INITIALIZATION_FAILED", error);
        }
    }

    synchronized State recovered() { return state; }

    synchronized void requireOwner() {
        try (PreparedStatement query = connection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            query.setString(1, lockName);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) { throw new SQLException("CATALOG_OWNER_LOST"); }
            }
        } catch (SQLException error) { throw new IllegalStateException("CATALOG_OWNER_LOST", error); }
    }

    synchronized void commit(String id, String event, KnowledgeCatalog next, long expectedGeneration) {
        try {
            requireOwner();
            State persisted = read();
            if (persisted.generation() != expectedGeneration || !digest(persisted).equals(digest(state))) {
                throw new IllegalStateException("CATALOG_GENERATION_CONFLICT");
            }
            Map<String, Row> rows = new TreeMap<>();
            state.rows().forEach(row -> rows.put(row.document().sourceId(), row));
            if (!event.equals("UNAVAILABLE")) {
                Row old = rows.get(id);
                if (old != null && old.tombstone() && (event.equals("ADD") || event.equals("UPDATE"))) {
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
            connection.commit();
            state = replacement;
        } catch (Exception error) {
            try { connection.rollback(); } catch (SQLException ignored) { /* 提交结果不明，由生命周期统一失败关闭。 */ }
            throw new IllegalStateException("CATALOG_COMMIT_FAILED_CLOSED", error);
        }
    }

    private State read() throws Exception {
        requireOwner();
        long generation; String expected;
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT id,generation,snapshot_sha256,schema_version FROM rag_catalog_state FOR UPDATE")) {
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
        try { connection.rollback(); } catch (SQLException ignored) { }
        try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            release.setString(1, lockName); release.execute();
        } catch (SQLException ignored) { }
        try { connection.close(); } catch (SQLException error) { throw new IllegalStateException("CATALOG_STORE_CLOSE_FAILED", error); }
    }
}
