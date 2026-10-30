package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReadOnlyCatalogTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement query = mock(Statement.class);
    private final ResultSet state = mock(ResultSet.class);
    private final ResultSet rows = mock(ResultSet.class);
    private final KnowledgeDocument document = new KnowledgeDocument("help-login", "登录帮助", "输入验证码登录。",
            "v1", "PLATFORM_HELP", "/login", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("登录"));

    private void validDatabase(boolean corruptChecksum) throws Exception {
        String contentHash = KnowledgeCatalog.hash(document.text());
        Map<String, String> history = Map.of(document.version(), contentHash);
        JdbcCatalogStore.State snapshot = new JdbcCatalogStore.State(2,
                List.of(new JdbcCatalogStore.Row(document, false, false, 1, history)));
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(query);
        when(query.executeQuery("SELECT id,generation,snapshot_sha256,schema_version FROM rag_catalog_state")).thenReturn(state);
        when(query.executeQuery("SELECT source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history FROM rag_source_state ORDER BY source_id")).thenReturn(rows);
        when(state.next()).thenReturn(true, false);
        when(state.getInt(1)).thenReturn(1);
        when(state.getInt(4)).thenReturn(1);
        when(state.getLong(2)).thenReturn(2L);
        when(state.getString(3)).thenReturn(corruptChecksum ? "0".repeat(64)
                : KnowledgeCatalog.hash(JSON.writeValueAsString(snapshot)));
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(document.sourceId());
        when(rows.getString(2)).thenReturn(document.version());
        when(rows.getString(3)).thenReturn(JSON.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(document));
        when(rows.getString(4)).thenReturn(contentHash);
        when(rows.getLong(5)).thenReturn(1L);
        when(rows.getString(8)).thenReturn(JSON.writeValueAsString(history));
    }

    @Test void readsVerifiedCommittedSnapshotWithoutOwnerOrWriteLocks() throws Exception {
        validDatabase(false);
        KnowledgeCatalog catalog = KnowledgeCatalog.readOnlySnapshot(source);
        assertEquals(2, catalog.epoch());
        assertEquals(document, catalog.source("help-login"));
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).setReadOnly(true);
        verify(connection).setAutoCommit(false);
        verify(connection, never()).prepareStatement(anyString());
        verify(query, never()).executeQuery(contains("GET_LOCK"));
        verify(query, never()).executeQuery(contains("FOR UPDATE"));
        verify(connection, never()).commit();
        verify(connection).rollback();
        verify(connection).close();
        assertFalse(catalog.lifecycle().durable());
        catalog.lifecycle().close();
    }

    @Test void readonlyLifecycleRejectsEveryCatalogMutationAndPublication() throws Exception {
        validDatabase(false);
        KnowledgeCatalog catalog = KnowledgeCatalog.readOnlySnapshot(source);
        CatalogLifecycle lifecycle = catalog.lifecycle();
        assertThrows(IllegalStateException.class, () -> lifecycle.add(document, 2));
        assertThrows(IllegalStateException.class, () -> lifecycle.update(document, 2));
        assertThrows(IllegalStateException.class, () -> lifecycle.revoke("help-login", 2));
        assertThrows(IllegalStateException.class, () -> lifecycle.delete("help-login", 2));
        assertThrows(IllegalStateException.class, () -> lifecycle.markUnavailable(2));
        assertThrows(IllegalStateException.class, lifecycle::beginPublication);
        assertThrows(IllegalStateException.class, () -> lifecycle.publish(List.of(document), 2, "test"));
        assertEquals(2, lifecycle.snapshot().epoch());
        assertEquals(document, lifecycle.snapshot().source("help-login"));
    }

    @Test void rejectsCorruptSnapshotAndClosesReadTransaction() throws Exception {
        validDatabase(true);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> KnowledgeCatalog.readOnlySnapshot(source));
        assertEquals("CATALOG_READ_ONLY_RECOVERY_FAILED", failure.getMessage());
        assertEquals("CATALOG_CHECKSUM_MISMATCH", failure.getCause().getMessage());
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).commit();
    }
}
