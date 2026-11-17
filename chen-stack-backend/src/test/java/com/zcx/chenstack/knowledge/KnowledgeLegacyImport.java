package com.zcx.chenstack.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

final class KnowledgeLegacyImport {
    private final JdbcTemplate jdbc;
    private final KnowledgeVectors vectors;
    private final TransactionTemplate transaction;

    public KnowledgeLegacyImport(JdbcTemplate jdbc, KnowledgeVectors vectors, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.vectors = vectors;
        this.transaction = new TransactionTemplate(manager);
    }

    private record Source(long id, String key, String version, String content) {}

    void run() {
        int expected = jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge WHERE enabled AND index_status='UNINDEXED'", Integer.class);
        if (expected == 0) {
            return;
        }
        List<Source> sources = jdbc.query("""
                SELECT k.id,d.source_key,s.version,k.content
                FROM rag_knowledge k JOIN knowledge_document_g4 d ON d.id=k.id
                JOIN rag_source_state s ON s.source_id=d.source_key
                WHERE k.enabled AND k.index_status='UNINDEXED' AND NOT s.deleted AND NOT s.tombstone
                  AND BINARY k.content=BINARY JSON_UNQUOTE(JSON_EXTRACT(s.document_json,'$.text'))
                ORDER BY k.id
                """, (rs, row) -> new Source(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        if (sources.size() != expected) {
            throw new IllegalStateException("历史正文和可信索引来源不完整");
        }
        for (Source source : sources) {
            var chunks = KnowledgeChunker.chunk(source.key(), source.version(), source.content());
            try {
                vectors.copyLegacy(source.key(), source.version(), source.content(), chunks);
            } catch (KnowledgeDocException failure) {
                throw new IllegalStateException("历史向量核验失败，knowledgeId=" + source.id(), failure);
            }
            transaction.executeWithoutResult(status -> {
                if (jdbc.update("UPDATE rag_knowledge SET revision=1,index_status='READY' WHERE id=? AND index_status='UNINDEXED'",
                        source.id()) != 1) {
                    throw new IllegalStateException("历史正文处理状态已变更");
                }
                jdbc.batchUpdate("INSERT INTO rag_chunk(knowledge_id,revision,chunk_no,content,qdrant_point_id) VALUES (?,1,?,?,?)",
                        chunks.stream().map(chunk -> new Object[]{source.id(), chunk.ordinal(), chunk.text(), chunk.pointId().toString()}).toList());
            });
        }
    }
}
