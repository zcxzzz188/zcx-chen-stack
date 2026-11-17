package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一知识文档模型的数据访问层。
 *
 * <p>只访问 {@code knowledge_*} 表。知识正文与审核状态的唯一权威是
 * {@code knowledge_document} / {@code knowledge_document_version}；
 * G3 受控发布账本（{@code rag_catalog_state} / {@code rag_source_state}）只由发布链写入，
 * 本层不写也不据此推导业务状态。需要事务的调用由上层服务通过 {@code TransactionTemplate} 包裹。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Repository
public class KnowledgeDocStore {
    /** 用户动作的审计操作人。 */
    public static final String ACTOR_USER = "USER";
    public static final String ACTOR_SYSTEM = "SYSTEM";
    public static final String ACTOR_MIGRATION = "MIGRATION";

    public record DocRow(long id, String sourceKey, String documentKind, String mirrorMode, String sourceRefKind,
                         String sourceUrl, String originRef, String title, String summary, String visibility,
                         String reviewStatus, String publishStatus, String legacyReviewStatus,
                         Long currentVersionId, Integer currentVersionNo, String contentSha256,
                         Long legacyGeneration, boolean tombstone, boolean deleted,
                         Integer createdBy, String createdByKind, Instant createdAt, Instant updatedAt) {
    }

    public record VersionRow(long id, long documentId, int versionNo, String versionLabel,
                             String rawBody, String normalizedBody, String rawFormat,
                             String normalizedSha256, String rawSha256, Integer charLength,
                             String chunkerVersion, String parserVersion,
                             boolean bodyAvailable, String bodyProvenance, String state,
                             String legacyStateRaw, Integer reviewedBy, Instant reviewedAt,
                             String approvedHash, Instant publishedAt, Long legacyGeneration,
                             Integer createdBy, String createdByKind, Instant createdAt) {
    }

    public record ChunkRow(long id, long versionId, String pointId, int ordinal, int startOffset, int endOffset,
                           String text, int charLength, String chunkSha256, String embeddingSignature) {
    }

    public record ImportRow(String requestId, String requestFingerprint, String originalFilename,
                            String contentType, long fileBytes, String fileSha256,
                            String bucketName, String objectKey, boolean bomPresent, String status,
                            String parserVersion, Integer parsedChars, String parsedTextSha256,
                            String rejectionCode, Long documentId, Long versionId,
                            int createdBy, Integer reviewedBy, Instant reviewedAt, Instant createdAt) {
    }

    public record IndexTaskRow(String requestId, long documentId, long versionId, String operation, String status,
                               String targetCollection, String indexAuthority, Long indexEpoch,
                               String embeddingModel, String embeddingSignature, int chunkCount,
                               int expectedEmbeddingCalls, int maxEmbeddingRequests, int embeddingRequests,
                               String fingerprintSha256, String lastErrorCode, Instant createdAt,
                               Instant completedAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public KnowledgeDocStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- 目录版本号

    /**
     * 知识目录版本号：由知识版本表与已发布集合推导出的单调观察值，只用于缓存失效与诊断。
     *
     * <p>它<b>不是</b>业务权威，也<b>不写</b>任何表。历史实现直接自增
     * {@code rag_catalog_state.generation}，与 G3 受控发布账本形成双写：一旦知识文档走
     * 审核/发布/撤销，账本 {@code snapshot_sha256} 立刻失真，下次启动会以
     * {@code CATALOG_CHECKSUM_MISMATCH} 失败关闭。改为纯推导后，发布代次只有一个写入者。</p>
     */
    public long revision() {
        Long revision = jdbc.queryForObject("""
                SELECT (SELECT COALESCE(MAX(v.id),0) FROM knowledge_document_version v
                          JOIN knowledge_document d ON d.id=v.document_id WHERE d.mirror_mode='AUTHORITATIVE')
                     + (SELECT COALESCE(SUM(CASE WHEN review_status='APPROVED' AND publish_status='PUBLISHED'
                                                 AND deleted=FALSE THEN 1 ELSE 0 END),0)
                        FROM knowledge_document WHERE mirror_mode='AUTHORITATIVE')
                     + (SELECT COALESCE(SUM(v.id),0) FROM knowledge_document_version v
                          JOIN knowledge_document d ON d.id=v.document_id
                        WHERE d.mirror_mode='AUTHORITATIVE' AND v.state='CURRENT')
                """, Long.class);
        return revision == null ? 0L : revision;
    }

    // ---------------------------------------------------------------- 文档

    private static final RowMapper<DocRow> DOC = (rs, n) -> new DocRow(
            rs.getLong("id"), rs.getString("source_key"), rs.getString("document_kind"),
            rs.getString("mirror_mode"), rs.getString("source_ref_kind"), rs.getString("source_url"),
            rs.getString("origin_ref"), rs.getString("title"), rs.getString("summary"),
            rs.getString("visibility"), rs.getString("review_status"), rs.getString("publish_status"),
            rs.getString("legacy_review_status"), (Long) rs.getObject("current_published_version_id", Long.class),
            rs.getObject("current_version_no", Integer.class), rs.getString("content_sha256"),
            (Long) rs.getObject("legacy_generation", Long.class), rs.getBoolean("tombstone"), rs.getBoolean("deleted"),
            rs.getObject("created_by", Integer.class), rs.getString("created_by_kind"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));

    private static final String DOC_COLUMNS = """
            id,source_key,document_kind,mirror_mode,source_ref_kind,source_url,origin_ref,title,summary,
            visibility,review_status,publish_status,legacy_review_status,current_published_version_id,
            current_version_no,content_sha256,legacy_generation,tombstone,deleted,created_by,created_by_kind,
            created_at,updated_at
            """;

    public long insertDocument(String sourceKey, String documentKind, String sourceRefKind, String sourceUrl,
                               String originRef, String title, String summary, String visibility,
                               Integer createdBy, String createdByKind) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO knowledge_document(source_key,document_kind,mirror_mode,source_ref_kind,source_url,
                        origin_ref,title,summary,visibility,review_status,publish_status,created_by,created_by_kind,
                        updated_by,updated_by_kind)
                    VALUES(?,?,'AUTHORITATIVE',?,?,?,?,?,?,'DRAFT','UNPUBLISHED',?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, sourceKey);
            insert.setString(2, documentKind);
            insert.setString(3, sourceRefKind);
            insert.setString(4, sourceUrl);
            insert.setString(5, originRef);
            insert.setString(6, title);
            insert.setString(7, summary);
            insert.setString(8, visibility);
            if (createdBy == null) {
                insert.setNull(9, java.sql.Types.INTEGER);
            } else {
                insert.setInt(9, createdBy);
            }
            insert.setString(10, createdByKind);
            if (createdBy == null) {
                insert.setNull(11, java.sql.Types.INTEGER);
            } else {
                insert.setInt(11, createdBy);
            }
            insert.setString(12, createdByKind);
            return insert;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 镜像写入：由回填器使用，冲突时更新可变的镜像字段。 */
    public void upsertMirrorDocument(String sourceKey, String title, String sourceUrl, String visibility,
                                     String reviewStatus, String publishStatus, String legacyReviewStatus,
                                     String contentSha256, Long legacyGeneration, boolean tombstone, boolean deleted,
                                     String keywordsJson) {
        jdbc.update("""
                INSERT INTO knowledge_document(source_key,document_kind,mirror_mode,source_ref_kind,source_url,
                    origin_ref,title,visibility,review_status,publish_status,legacy_review_status,content_sha256,
                    legacy_generation,tombstone,deleted,keywords_json,created_by,created_by_kind,updated_by,updated_by_kind)
                VALUES(?,'PLATFORM_HELP','MIRROR','PLATFORM_PAGE',?,'legacy-rag',?,?,?,?,?,?,?,?,?,?,NULL,'MIGRATION',NULL,'MIGRATION')
                ON DUPLICATE KEY UPDATE title=VALUES(title),source_url=VALUES(source_url),visibility=VALUES(visibility),
                    review_status=VALUES(review_status),publish_status=VALUES(publish_status),
                    legacy_review_status=VALUES(legacy_review_status),content_sha256=VALUES(content_sha256),
                    legacy_generation=VALUES(legacy_generation),tombstone=VALUES(tombstone),deleted=VALUES(deleted),
                    keywords_json=VALUES(keywords_json),updated_by_kind='MIGRATION'
                """, sourceKey, sourceUrl, title, visibility, reviewStatus, publishStatus, legacyReviewStatus,
                contentSha256, legacyGeneration, tombstone, deleted, keywordsJson);
    }

    public Optional<DocRow> findDocument(long id) {
        List<DocRow> rows = jdbc.query("SELECT " + DOC_COLUMNS + " FROM knowledge_document WHERE id=?", DOC, id);
        return rows.stream().findFirst();
    }

    public Optional<DocRow> findDocumentBySourceKey(String sourceKey) {
        List<DocRow> rows = jdbc.query("SELECT " + DOC_COLUMNS + " FROM knowledge_document WHERE source_key=?",
                DOC, sourceKey);
        return rows.stream().findFirst();
    }

    public List<DocRow> listDocuments(String query, boolean authoritativeOnly, int limit) {
        String q = query == null ? "" : query.strip();
        return jdbc.query("SELECT " + DOC_COLUMNS + """
                FROM knowledge_document
                WHERE (? = '' OR LOCATE(?, title) > 0 OR LOCATE(?, source_key) > 0)
                  AND (? = FALSE OR mirror_mode = 'AUTHORITATIVE')
                ORDER BY updated_at DESC, id DESC LIMIT ?
                """, DOC, q, q, q, authoritativeOnly, limit);
    }

    public void updateReviewStatus(long id, String reviewStatus) {
        jdbc.update("UPDATE knowledge_document SET review_status=? WHERE id=?", reviewStatus, id);
    }

    public void updateVisibility(long id, String visibility) {
        jdbc.update("UPDATE knowledge_document SET visibility=? WHERE id=?", visibility, id);
    }

    public void updatePublishStatus(long id, String publishStatus) {
        jdbc.update("UPDATE knowledge_document SET publish_status=? WHERE id=?", publishStatus, id);
    }

    /** 发布：把指针、发布状态、内容哈希一次性切换。 */
    public void markPublished(long documentId, long versionId, int versionNo, String contentSha256) {
        int updated = jdbc.update("""
                UPDATE knowledge_document
                SET current_published_version_id=?, current_version_no=?, content_sha256=?,
                    publish_status='PUBLISHED'
                WHERE id=?
                """, versionId, versionNo, contentSha256, documentId);
        if (updated != 1) {
            throw new IllegalStateException("KNOWLEDGE_DOCUMENT_MISSING");
        }
    }

    public void markWithdrawn(long documentId) {
        jdbc.update("UPDATE knowledge_document SET publish_status='WITHDRAWN' WHERE id=?", documentId);
    }

    public void markRevoked(long documentId) {
        jdbc.update("""
                UPDATE knowledge_document SET review_status='REVOKED', publish_status='WITHDRAWN' WHERE id=?
                """, documentId);
    }

    public void updateDocumentTitle(long documentId, String title, String summary) {
        jdbc.update("UPDATE knowledge_document SET title=?, summary=? WHERE id=?", title, summary, documentId);
    }

    // ---------------------------------------------------------------- 版本

    private static final RowMapper<VersionRow> VERSION = (rs, n) -> new VersionRow(
            rs.getLong("id"), rs.getLong("document_id"), rs.getInt("version_no"), rs.getString("version_label"),
            rs.getString("raw_body"), rs.getString("normalized_body"), rs.getString("raw_format"),
            rs.getString("normalized_sha256"), rs.getString("raw_sha256"),
            rs.getObject("char_length", Integer.class), rs.getString("chunker_version"),
            rs.getString("parser_version"), rs.getBoolean("body_available"), rs.getString("body_provenance"),
            rs.getString("state"), rs.getString("legacy_state_raw"),
            rs.getObject("reviewed_by", Integer.class), instant(rs.getTimestamp("reviewed_at")),
            rs.getString("approved_hash"), instant(rs.getTimestamp("published_at")),
            (Long) rs.getObject("legacy_generation", Long.class), rs.getObject("created_by", Integer.class),
            rs.getString("created_by_kind"), instant(rs.getTimestamp("created_at")));

    private static final String VERSION_COLUMNS = """
            id,document_id,version_no,version_label,raw_body,normalized_body,raw_format,normalized_sha256,
            raw_sha256,char_length,chunker_version,parser_version,body_available,body_provenance,state,
            legacy_state_raw,reviewed_by,reviewed_at,approved_hash,published_at,legacy_generation,created_by,
            created_by_kind,created_at
            """;

    public long insertVersion(long documentId, int versionNo, String versionLabel, String rawBody,
                              String normalizedBody, String rawFormat, String normalizedSha256, String rawSha256,
                              int charLength, String parserVersion, String state,
                              Integer createdBy, String createdByKind) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,
                        normalized_body,raw_format,normalized_sha256,raw_sha256,char_length,chunker_version,
                        parser_version,body_available,body_provenance,state,created_by,created_by_kind)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,TRUE,'IMPORT',?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            insert.setLong(1, documentId);
            insert.setInt(2, versionNo);
            insert.setString(3, versionLabel);
            insert.setString(4, rawBody);
            insert.setString(5, normalizedBody);
            insert.setString(6, rawFormat);
            insert.setString(7, normalizedSha256);
            insert.setString(8, rawSha256);
            insert.setInt(9, charLength);
            insert.setString(10, KnowledgeChunker.VERSION);
            insert.setString(11, parserVersion);
            insert.setString(12, state);
            if (createdBy == null) {
                insert.setNull(13, java.sql.Types.INTEGER);
            } else {
                insert.setInt(13, createdBy);
            }
            insert.setString(14, createdByKind);
            return insert;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 回填：无正文的历史版本。 */
    public void upsertHashOnlyVersion(long documentId, int versionNo, String versionLabel, String normalizedSha256,
                                      String legacyStateRaw, Long legacyGeneration) {
        jdbc.update("""
                INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,normalized_body,
                    raw_format,normalized_sha256,char_length,body_available,body_provenance,state,legacy_state_raw,
                    legacy_generation,created_by_kind)
                VALUES(?,?,?,NULL,NULL,'TEXT',?,NULL,FALSE,'UNAVAILABLE','SUPERSEDED',?,?, 'MIGRATION')
                ON DUPLICATE KEY UPDATE normalized_sha256=VALUES(normalized_sha256),
                    legacy_state_raw=COALESCE(knowledge_document_version.legacy_state_raw, VALUES(legacy_state_raw)),
                    legacy_generation=VALUES(legacy_generation)
                """, documentId, versionNo, versionLabel, normalizedSha256, legacyStateRaw, legacyGeneration);
    }

    /** 回填：带正文的历史版本（仅当正文真实可得时调用）。 */
    public void upsertBodyVersion(long documentId, int versionNo, String versionLabel, String rawBody,
                                  String normalizedBody, String normalizedSha256, int charLength,
                                  String state, String provenance, String legacyStateRaw, Long legacyGeneration) {
        jdbc.update("""
                INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,normalized_body,
                    raw_format,normalized_sha256,char_length,body_available,body_provenance,state,legacy_state_raw,
                    legacy_generation,created_by_kind)
                VALUES(?,?,?,?,?,'TEXT',?,?,TRUE,?,'SUPERSEDED',?,?,'MIGRATION')
                ON DUPLICATE KEY UPDATE raw_body=VALUES(raw_body),normalized_body=VALUES(normalized_body),
                    normalized_sha256=VALUES(normalized_sha256),char_length=VALUES(char_length),
                    body_available=TRUE,body_provenance=VALUES(body_provenance),
                    legacy_state_raw=COALESCE(knowledge_document_version.legacy_state_raw, VALUES(legacy_state_raw)),
                    legacy_generation=VALUES(legacy_generation)
                """, documentId, versionNo, versionLabel, rawBody, normalizedBody, normalizedSha256, charLength,
                provenance, state, legacyStateRaw, legacyGeneration);
    }

    public Optional<VersionRow> findVersion(long id) {
        List<VersionRow> rows = jdbc.query("SELECT " + VERSION_COLUMNS + " FROM knowledge_document_version WHERE id=?",
                VERSION, id);
        return rows.stream().findFirst();
    }

    public Optional<VersionRow> findVersionByLabel(long documentId, String versionLabel) {
        List<VersionRow> rows = jdbc.query("SELECT " + VERSION_COLUMNS
                + " FROM knowledge_document_version WHERE document_id=? AND version_label=?", VERSION,
                documentId, versionLabel);
        return rows.stream().findFirst();
    }

    public List<VersionRow> listVersions(long documentId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS
                + " FROM knowledge_document_version WHERE document_id=? ORDER BY version_no DESC", VERSION, documentId);
    }

    public int nextVersionNo(long documentId) {
        Integer next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_document_version WHERE document_id=?",
                Integer.class, documentId);
        return next == null ? 1 : next;
    }

    public void demoteCurrent(long documentId) {
        jdbc.update("UPDATE knowledge_document_version SET state='SUPERSEDED' WHERE document_id=? AND state='CURRENT'",
                documentId);
    }

    public void promoteCurrent(long versionId) {
        jdbc.update("UPDATE knowledge_document_version SET state='CURRENT', published_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                versionId);
    }

    public void approveVersion(long versionId, int reviewerId, String approvedHash) {
        jdbc.update("""
                UPDATE knowledge_document_version SET reviewed_by=?, reviewed_at=CURRENT_TIMESTAMP(6), approved_hash=?
                WHERE id=?
                """, reviewerId, approvedHash, versionId);
    }

    public void demoteDraft(long documentId) {
        jdbc.update("UPDATE knowledge_document_version SET state='SUPERSEDED' WHERE document_id=? AND state='DRAFT'",
                documentId);
    }

    /** 统计各 provenance 分布，供对账报表使用。 */
    public List<Map<String, Object>> provenanceReport() {
        return jdbc.queryForList("""
                SELECT body_provenance, body_available, COUNT(*) AS rows
                FROM knowledge_document_version GROUP BY body_provenance, body_available
                ORDER BY rows DESC
                """);
    }

    // ---------------------------------------------------------------- 分块

    private static final RowMapper<ChunkRow> CHUNK = (rs, n) -> new ChunkRow(
            rs.getLong("id"), rs.getLong("version_id"), rs.getString("point_id"), rs.getInt("ordinal"),
            rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("chunk_text"),
            rs.getInt("chunk_char_length"), rs.getString("chunk_sha256"),
            rs.getString("embedding_signature"));

    public void deleteChunks(long versionId) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE version_id=?", versionId);
    }

    public void insertChunks(long versionId, List<KnowledgeChunker.Chunk> chunks, String embeddingSignature) {
        jdbc.batchUpdate("""
                INSERT INTO knowledge_chunk(version_id,point_id,ordinal,start_offset,end_offset,chunk_text,
                    chunk_char_length,chunk_sha256,chunker_version,embedding_signature)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, chunks.stream().map(chunk -> new Object[]{
                versionId, chunk.pointIdText(), chunk.ordinal(), chunk.startOffset(), chunk.endOffset(),
                chunk.text(), chunk.text().length(), chunk.chunkSha256(), KnowledgeChunker.VERSION, embeddingSignature
        }).toList());
    }

    public List<ChunkRow> listChunks(long versionId) {
        return jdbc.query("""
                SELECT id,version_id,point_id,ordinal,start_offset,end_offset,chunk_text,chunk_char_length,
                       chunk_sha256,embedding_signature
                FROM knowledge_chunk WHERE version_id=? ORDER BY ordinal
                """, CHUNK, versionId);
    }

    public List<ChunkRow> listPublishedChunks() {
        return jdbc.query("""
                SELECT c.id,c.version_id,c.point_id,c.ordinal,c.start_offset,c.end_offset,c.chunk_text,
                       c.chunk_char_length,c.chunk_sha256,c.embedding_signature
                FROM knowledge_chunk c
                JOIN knowledge_document_version v ON v.id=c.version_id AND v.state='CURRENT'
                JOIN knowledge_document d ON d.id=v.document_id
                WHERE d.publish_status='PUBLISHED' AND d.review_status='APPROVED'
                  AND d.visibility='PUBLIC' AND d.deleted=FALSE AND d.mirror_mode='AUTHORITATIVE'
                ORDER BY c.id
                """, CHUNK);
    }

    /**
     * 已发布的本库文档当前版本（知识库检索快照的唯一来源）。
     *
     * <p>只取 {@code AUTHORITATIVE}：{@code MIRROR} 行是迁移期对 G3 平台帮助账本的只读投影，
     * 其正文与状态权威仍在 {@code rag_source_state}，由受控发布链提供，不能在此重复取用。</p>
     */
    public record PublishedVersionRow(long documentId, long versionId, String sourceKey, String title,
                                      String summary, String versionLabel, String normalizedSha256,
                                      String sourceRefKind, String sourceUrl, Instant publishedAt) {
    }

    public List<PublishedVersionRow> listPublishedVersions() {
        return jdbc.query("""
                SELECT d.id AS document_id, v.id AS version_id, d.source_key, d.title, d.summary,
                       v.version_label, v.normalized_sha256, d.source_ref_kind, d.source_url, v.published_at
                FROM knowledge_document d
                JOIN knowledge_document_version v ON v.id = d.current_published_version_id
                WHERE d.publish_status='PUBLISHED' AND d.review_status='APPROVED'
                  AND d.visibility='PUBLIC' AND d.deleted=FALSE AND v.state='CURRENT'
                  AND d.mirror_mode='AUTHORITATIVE'
                ORDER BY d.id
                """, (rs, n) -> new PublishedVersionRow(
                rs.getLong("document_id"), rs.getLong("version_id"), rs.getString("source_key"),
                rs.getString("title"), rs.getString("summary"), rs.getString("version_label"),
                rs.getString("normalized_sha256"), rs.getString("source_ref_kind"), rs.getString("source_url"),
                instant(rs.getTimestamp("published_at"))));
    }

    public List<ChunkRow> listChunksForVersions(List<Long> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(versionIds.size(), "?"));
        return jdbc.query("""
                SELECT id,version_id,point_id,ordinal,start_offset,end_offset,chunk_text,chunk_char_length,
                       chunk_sha256,embedding_signature
                FROM knowledge_chunk WHERE version_id IN (""" + placeholders + ") ORDER BY version_id, ordinal",
                CHUNK, versionIds.toArray());
    }

    public int countChunks(long versionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE version_id=?",
                Integer.class, versionId);
        return count == null ? 0 : count;
    }

    public void clearEmbeddingSignature(long versionId) {
        jdbc.update("UPDATE knowledge_chunk SET embedding_signature=NULL WHERE version_id=?", versionId);
    }

    // ---------------------------------------------------------------- 索引任务

    private static final RowMapper<IndexTaskRow> INDEX_TASK = (rs, n) -> new IndexTaskRow(
            rs.getString("request_id"), rs.getLong("document_id"), rs.getLong("version_id"),
            rs.getString("operation"), rs.getString("status"), rs.getString("target_collection"),
            rs.getString("index_authority"), (Long) rs.getObject("index_epoch", Long.class),
            rs.getString("embedding_model"), rs.getString("embedding_signature"), rs.getInt("chunk_count"),
            rs.getInt("expected_embedding_calls"), rs.getInt("max_embedding_requests"),
            rs.getInt("embedding_requests"), rs.getString("index_fingerprint_sha256"),
            rs.getString("last_error_code"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("completed_at")));

    public void insertIndexTask(String requestId, long documentId, long versionId, String operation,
                                String collection, String model, String signature, int chunkCount,
                                int expectedCalls, int maxCalls, Integer createdBy) {
        jdbc.update("""
                INSERT INTO knowledge_index_task(request_id,document_id,version_id,operation,status,target_collection,
                    embedding_model,embedding_signature,chunk_count,expected_embedding_calls,max_embedding_requests,
                    embedding_requests,created_by,created_by_kind)
                VALUES(?,?,?,?,'PREPARED',?,?,?,?,?,?,0,?,'USER')
                ON DUPLICATE KEY UPDATE request_id=request_id
                """, requestId, documentId, versionId, operation, collection, model, signature, chunkCount,
                expectedCalls, maxCalls, createdBy);
    }

    public void updateIndexStatus(String requestId, String status, String errorCode) {
        jdbc.update("UPDATE knowledge_index_task SET status=?, last_error_code=? WHERE request_id=?",
                status, errorCode, requestId);
    }

    public void markIndexing(String requestId, Long epoch) {
        jdbc.update("UPDATE knowledge_index_task SET status='INDEXING', index_epoch=? WHERE request_id=?",
                epoch, requestId);
    }

    public void reserveEmbedding(String requestId) {
        int updated = jdbc.update("""
                UPDATE knowledge_index_task SET embedding_requests=embedding_requests+1
                WHERE request_id=? AND status='INDEXING' AND embedding_requests < max_embedding_requests
                """, requestId);
        if (updated != 1) {
            throw KnowledgeDocException.conflict("INDEX_BUDGET_EXHAUSTED", "索引预算已用尽");
        }
    }

    public void completeIndexTask(String requestId, String authority, long epoch, String fingerprint) {
        jdbc.update("""
                UPDATE knowledge_index_task SET status='COMPLETED', index_authority=?, index_epoch=?,
                    index_fingerprint_sha256=?, completed_at=CURRENT_TIMESTAMP(6), last_error_code=NULL
                WHERE request_id=?
                """, authority, epoch, fingerprint, requestId);
    }

    public void failIndexTask(String requestId, String errorCode) {
        jdbc.update("""
                UPDATE knowledge_index_task SET status='FAILED', last_error_code=? WHERE request_id=?
                """, errorCode, requestId);
    }

    public void markIndexUnknown(String requestId, String errorCode) {
        jdbc.update("""
                UPDATE knowledge_index_task SET status='UNKNOWN', last_error_code=? WHERE request_id=?
                """, errorCode, requestId);
    }

    public Optional<IndexTaskRow> findIndexTask(String requestId) {
        List<IndexTaskRow> rows = jdbc.query("""
                SELECT request_id,document_id,version_id,operation,status,target_collection,index_authority,index_epoch,
                       embedding_model,embedding_signature,chunk_count,expected_embedding_calls,max_embedding_requests,
                       embedding_requests,index_fingerprint_sha256,last_error_code,created_at,completed_at
                FROM knowledge_index_task WHERE request_id=?
                """, INDEX_TASK, requestId);
        return rows.stream().findFirst();
    }

    public Optional<IndexTaskRow> latestCompleted(long versionId) {
        List<IndexTaskRow> rows = jdbc.query("""
                SELECT request_id,document_id,version_id,operation,status,target_collection,index_authority,index_epoch,
                       embedding_model,embedding_signature,chunk_count,expected_embedding_calls,max_embedding_requests,
                       embedding_requests,index_fingerprint_sha256,last_error_code,created_at,completed_at
                FROM knowledge_index_task
                WHERE version_id=? AND status='COMPLETED'
                ORDER BY completed_at DESC LIMIT 1
                """, INDEX_TASK, versionId);
        return rows.stream().findFirst();
    }

    public List<IndexTaskRow> listIndexTasks(long documentId, int limit) {
        return jdbc.query("""
                SELECT request_id,document_id,version_id,operation,status,target_collection,index_authority,index_epoch,
                       embedding_model,embedding_signature,chunk_count,expected_embedding_calls,max_embedding_requests,
                       embedding_requests,index_fingerprint_sha256,last_error_code,created_at,completed_at
                FROM knowledge_index_task WHERE document_id=? ORDER BY created_at DESC LIMIT ?
                """, INDEX_TASK, documentId, limit);
    }

    // ---------------------------------------------------------------- 导入任务

    private static final RowMapper<ImportRow> IMPORT = (rs, n) -> new ImportRow(
            rs.getString("request_id"), rs.getString("request_fingerprint"), rs.getString("original_filename"),
            rs.getString("content_type"), rs.getLong("file_bytes"), rs.getString("file_sha256"),
            rs.getString("bucket_name"), rs.getString("object_key"), rs.getBoolean("bom_present"),
            rs.getString("status"), rs.getString("parse_parser_version"),
            rs.getObject("parsed_chars", Integer.class), rs.getString("parsed_text_sha256"),
            rs.getString("rejection_code"), (Long) rs.getObject("document_id", Long.class),
            (Long) rs.getObject("version_id", Long.class), rs.getInt("created_by"),
            rs.getObject("reviewed_by", Integer.class), instant(rs.getTimestamp("reviewed_at")),
            instant(rs.getTimestamp("created_at")));

    private static final String IMPORT_COLUMNS = """
            request_id,request_fingerprint,original_filename,content_type,file_bytes,file_sha256,bucket_name,
            object_key,bom_present,status,parse_parser_version,parsed_chars,parsed_text_sha256,rejection_code,
            document_id,version_id,created_by,reviewed_by,reviewed_at,created_at
            """;

    /** 幂等写入；同 request_id 已存在时返回既有行，由上层比对指纹。 */
    public ImportRow insertImportIfAbsent(ImportRow row) {
        try {
            jdbc.update("""
                    INSERT INTO knowledge_import_task(request_id,request_fingerprint,original_filename,content_type,
                        file_bytes,file_sha256,storage_backend,bucket_name,object_key,charset,bom_present,status,
                        parse_parser_version,parsed_chars,parsed_text_sha256,created_by)
                    VALUES(?,?,?,?,?,?,'MINIO_PRIVATE',?,?, 'UTF-8',?,?,?,?,?,?)
                    """, row.requestId(), row.requestFingerprint(), row.originalFilename(), row.contentType(),
                    row.fileBytes(), row.fileSha256(), row.bucketName(), row.objectKey(), row.bomPresent(),
                    row.status(), row.parserVersion(), row.parsedChars(), row.parsedTextSha256(), row.createdBy());
        } catch (DuplicateKeyException ignored) {
            // 幂等：同 key 复用既有任务
        }
        return findImport(row.requestId()).orElseThrow(() -> KnowledgeDocException.unavailable(
                "IMPORT_PERSIST_FAILED", "导入任务写入失败"));
    }

    public Optional<ImportRow> findImport(String requestId) {
        List<ImportRow> rows = jdbc.query("SELECT " + IMPORT_COLUMNS
                + " FROM knowledge_import_task WHERE request_id=?", IMPORT, requestId);
        return rows.stream().findFirst();
    }

    public List<ImportRow> listImports(String status, int limit) {
        String filter = status == null ? "" : status;
        return jdbc.query("SELECT " + IMPORT_COLUMNS + """
                FROM knowledge_import_task
                WHERE (? = '' OR status = ?)
                ORDER BY created_at DESC LIMIT ?
                """, IMPORT, filter, filter, limit);
    }

    public void attachImportDocument(String requestId, long documentId, long versionId) {
        jdbc.update("""
                UPDATE knowledge_import_task SET document_id=?, version_id=? WHERE request_id=?
                """, documentId, versionId, requestId);
    }

    public void updateImportStatus(String requestId, String status, String rejectionCode, Integer reviewerId) {
        jdbc.update("""
                UPDATE knowledge_import_task SET status=?, rejection_code=?,
                    reviewed_by=COALESCE(?, reviewed_by), reviewed_at=CURRENT_TIMESTAMP(6)
                WHERE request_id=?
                """, status, rejectionCode, reviewerId, requestId);
    }

    /**
     * 导入任务的 AI 分析结果（权威快照）。
     *
     * <p>状态、模型、失败原因与候选快照都是 {@code knowledge_import_task} 的列；
     * {@code knowledge_audit_log} 只记录"谁在何时做了什么"，不再承载可恢复业务状态。</p>
     */
    public record ImportAnalysis(String status, String model, String resultJson, String errorCode,
                                 String errorMessage, int attempts, Instant analyzedAt) {
    }

    /** 读取导入任务的权威分析状态；尚未分析时返回空。 */
    public Optional<ImportAnalysis> latestImportAnalysis(String requestId) {
        List<ImportAnalysis> rows = jdbc.query("""
                SELECT analysis_status,analysis_model,CAST(analysis_result_json AS CHAR),
                       analysis_error_code,analysis_error_message,analysis_attempts,analyzed_at
                FROM knowledge_import_task WHERE request_id=?
                """, (rs, index) -> new ImportAnalysis(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6), instant(rs.getTimestamp(7))), requestId);
        return rows.stream().filter(row -> !"NOT_ANALYZED".equals(row.status())).findFirst();
    }

    /** 写入分析结果；每次调用都会递增尝试次数，便于识别重复分析。 */
    public void recordAnalysis(String requestId, String status, String model, String resultJson,
                               String errorCode, String errorMessage) {
        int updated = jdbc.update("""
                UPDATE knowledge_import_task
                SET analysis_status=?,analysis_model=?,analysis_result_json=?,analysis_error_code=?,
                    analysis_error_message=?,analysis_attempts=analysis_attempts+1,analyzed_at=CURRENT_TIMESTAMP(6)
                WHERE request_id=?
                """, status, model, resultJson, errorCode, errorMessage, requestId);
        if (updated != 1) {
            throw KnowledgeDocException.missing();
        }
    }

    /** 同一对象被多少个仍存活的导入任务引用；用于安全删除原件。 */
    public int countImportRefs(String fileSha256) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_import_task WHERE file_sha256=? AND status<>'REJECTED'",
                Integer.class, fileSha256);
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 审计

    public void audit(String requestId, Long documentId, Long versionId, String importTaskId,
                      Integer actorId, String actorKind, String actorRole, String action, String targetKind,
                      String beforeSha256, String afterSha256, String reasonCode, Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null || detail.isEmpty() ? null : json.writeValueAsString(detail);
        } catch (Exception error) {
            detailJson = null;
        }
        jdbc.update("""
                INSERT INTO knowledge_audit_log(request_id,document_id,version_id,import_task_id,actor_id,actor_kind,
                    actor_role,action,target_kind,before_sha256,after_sha256,reason_code,detail_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, requestId, documentId, versionId, importTaskId, actorId, actorKind, actorRole, action,
                targetKind, beforeSha256, afterSha256, reasonCode, detailJson);
    }

    public void auditSystem(String action, String targetKind, String reasonCode, Map<String, Object> detail) {
        audit(null, null, null, null, null, ACTOR_SYSTEM, null, action, targetKind, null, null, reasonCode, detail);
    }

    /** 统一 JSON 序列化入口；失败视为实现错误，不静默写出半截快照。 */
    public String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("KNOWLEDGE_SNAPSHOT_INVALID", error);
        }
    }

    public List<Map<String, Object>> recentAudit(long documentId, int limit) {
        return jdbc.queryForList("""
                SELECT id,request_id,actor_id,actor_kind,action,target_kind,reason_code,created_at
                FROM knowledge_audit_log WHERE document_id=? ORDER BY id DESC LIMIT ?
                """, documentId, limit);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
