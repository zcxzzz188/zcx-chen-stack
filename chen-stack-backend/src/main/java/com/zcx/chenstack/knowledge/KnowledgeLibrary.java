package com.zcx.chenstack.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@lombok.extern.slf4j.Slf4j
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
public class KnowledgeLibrary {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeFiles files;
    private final KnowledgeVectors vectors;
    private final KnowledgeWikiGenerator wiki;

    public KnowledgeLibrary(JdbcTemplate jdbc, PlatformTransactionManager manager, KnowledgeFiles files,
                            KnowledgeVectors vectors, KnowledgeWikiGenerator wiki) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.files = files;
        this.vectors = vectors;
        this.wiki = wiki;
    }

    public record Original(long id, String title, String filename, int size, String type, String status,
                           LocalDateTime updatedAt, int topicCount, boolean curated) {}
    public record Download(String filename, byte[] bytes) {}
    public record Entry(long id, Long documentId, String title, String filename, String content, boolean enabled,
                        String status, LocalDateTime updatedAt, String module) {}

    private static final String DOCUMENTS = """
                SELECT d.id,d.title,d.original_filename,d.file_size,d.file_type,
                       GREATEST(d.created_at,COALESCE(MAX(k.updated_at),d.created_at)) AS updated_at,
                       CASE WHEN d.file_path IS NULL THEN 'UNINDEXED'
                            WHEN MAX(k.index_status='FAILED' OR
                                     (k.enabled AND k.index_status='READY' AND k.retry_at<=NOW(6) AND k.index_attempts>=3)) THEN 'FAILED'
                            WHEN MAX(k.index_status='PENDING' OR
                                     (k.enabled AND k.index_status='READY' AND k.retry_at IS NOT NULL)) THEN 'PENDING'
                            WHEN MAX(k.enabled AND k.index_status='UNINDEXED') THEN 'UNINDEXED'
                            ELSE 'READY' END AS status,COUNT(k.id) AS topic_count,d.file_path
                FROM knowledge_document d
                LEFT JOIN rag_knowledge k ON k.document_id=d.id AND k.index_status<>'DELETING'
                WHERE NOT d.deleting
                GROUP BY d.id
                """;
    private static final RowMapper<Original> ORIGINAL = (rs, row) ->
            new Original(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5), rs.getString(7), rs.getObject(6, LocalDateTime.class),
                rs.getInt(8), KnowledgeFiles.isCurated(rs.getString(9)));

    public List<Original> documents(int offset) {
        return jdbc.query(DOCUMENTS + " ORDER BY d.id DESC LIMIT 51 OFFSET ?", ORIGINAL, offset);
    }

    public KnowledgeList<Original> documents(KnowledgeList.Filter filter, Boolean curated, String state) {
        String from = " FROM (" + DOCUMENTS + """
                ) d WHERE LOCATE(?,d.title)>0
                  AND (? IS NULL OR COALESCE(d.file_path LIKE 'curated/%',FALSE)=?)
                  AND (? IS NULL OR d.status=?)
                  AND (? IS NULL OR d.updated_at>=?) AND (? IS NULL OR d.updated_at<=?)
                """;
        return KnowledgeList.query(jdbc, filter, from, "d.id DESC", ORIGINAL, filter.keyword(),
                curated, curated, state, state, filter.from(), filter.from(), filter.to(), filter.to());
    }

    public Download download(long id) {
        return jdbc.query("""
                SELECT original_filename,file_path,file_size FROM knowledge_document WHERE id=? AND NOT deleting
                """, (rs, row) -> new Download(rs.getString(1), files.read(rs.getString(2), rs.getInt(3))), id)
                .stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    public byte[] downloadArchive(List<Long> ids) {
        var buffer = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(buffer)) {
            for (long id : ids.stream().distinct().toList()) {
                Download file;
                try {
                    file = download(id);
                } catch (KnowledgeDocException unavailable) {
                    throw KnowledgeDocException.conflict("KNOWLEDGE_ARCHIVE_UNAVAILABLE",
                            "文档 #" + id + " 无法读取，请刷新列表后重试");
                }
                String filename = file.filename().replaceAll("[/\\\\:\\p{Cntrl}]", "_");
                zip.putNextEntry(new ZipEntry(id + "-" + filename));
                zip.write(file.bytes());
                zip.closeEntry();
            }
        } catch (IOException failure) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_ARCHIVE_FAILED", "暂时无法生成下载文件");
        }
        return buffer.toByteArray();
    }

    public synchronized long upload(String filename, byte[] bytes, Long replaceId) {
        String extension = KnowledgeTextParser.extensionOf(filename);
        if ((!extension.equals("md") && !extension.equals("txt")) || filename.length() > 255
                || filename.contains("/") || filename.contains("\\") || filename.chars().anyMatch(Character::isISOControl)) {
            throw KnowledgeDocException.bad("KNOWLEDGE_FILE_INVALID", "请选择有效的 UTF-8 Markdown 或 TXT 文件");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > KnowledgeTextParser.MAX_FILE_BYTES) {
            throw KnowledgeDocException.bad("KNOWLEDGE_FILE_INVALID", "文件不能为空或超过 2 MiB");
        }
        byte[] digest = KnowledgeFiles.sha256(bytes);
        Long existing = duplicate(digest, replaceId, false);
        if (existing != null) {
            return existing;
        }
        Long target = replaceId == null ? findDocumentByFilename(filename) : replaceId;
        long started = System.nanoTime();
        var parsed = KnowledgeDocumentTopics.parse(filename, bytes, extension.equals("md"), wiki);
        String type = extension.equals("md") ? "MD" : "TXT";
        log.info("knowledge stage=parse type={} topics={} millis={}", type, parsed.topics().size(), elapsed(started));
        long persisted = System.nanoTime();
        long id = transaction.execute(status -> {
            Long duplicate = duplicate(digest, target, true);
            if (duplicate != null) {
                return duplicate;
            }
            if (target == null) {
                if (parsed.topics().stream().anyMatch(topic -> topic.id() != null)) {
                    throw KnowledgeDocException.conflict("KNOWLEDGE_CHAPTER_SCOPE", "带章节标记的文件请选择原文档替换");
                }
                String path = saveOriginal(bytes, extension);
                jdbc.update("""
                        INSERT INTO knowledge_document(title,original_filename,file_path,file_type,file_size,file_sha256)
                        VALUES (?,?,?,?,?,?)
                        """, parsed.title(), filename, path, type, bytes.length, digest);
                long documentId = lastId();
                parsed.topics().forEach(topic -> insertEntry(documentId, topic.title(), topic.content(), true));
                return documentId;
            }
            var document = lockDocument(target);
            List<Chapter> previous = chapters(target);
            List<KnowledgeDocumentTopics.Topic> topics = replaceTopics(previous, parsed.topics(), target);
            String originalPath = saveOriginal(bytes, extension);
            if (KnowledgeFiles.isCurated(document.path())) {
                byte[] master = KnowledgeDocumentTopics.render(parsed.title(), topics);
                pointToFile(target, parsed.title(), document.filename(), files.saveCurated(master), "MD", master);
            } else {
                pointToFile(target, parsed.title(), filename, originalPath, type, bytes);
            }
            return target;
        });
        log.info("knowledge stage=upload_commit document={} millis={}", id, elapsed(persisted));
        return id;
    }

    private String saveOriginal(byte[] bytes, String extension) {
        long started = System.nanoTime();
        String path = files.save(bytes, extension);
        log.info("knowledge stage=save_original bytes={} millis={}", bytes.length, elapsed(started));
        return path;
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private Long findDocumentByFilename(String filename) {
        var ids = jdbc.queryForList("""
                SELECT id FROM knowledge_document WHERE original_filename=? AND NOT deleting ORDER BY id LIMIT 2
                """, Long.class, filename);
        if (ids.size() > 1) {
            throw KnowledgeDocException.conflict("KNOWLEDGE_DUPLICATE_NAME", "存在多个同名文档，请从列表选择要替换的文档");
        }
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Long duplicate(byte[] digest, Long replaceId, boolean lock) {
        var ids = jdbc.query("SELECT id,deleting FROM knowledge_document WHERE file_sha256=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> {
                    if (rs.getBoolean(2)) {
                        throw busy();
                    }
                    return rs.getLong(1);
                }, digest);
        if (ids.isEmpty()) {
            return null;
        }
        if (replaceId != null && !replaceId.equals(ids.getFirst())) {
            throw KnowledgeDocException.conflict("KNOWLEDGE_DUPLICATE", "相同文件已经上传");
        }
        return ids.getFirst();
    }

    private record DocumentFile(String title, String filename, String path) {}
    private record Chapter(long id, String title, String content, boolean enabled, String state) {}

    private DocumentFile lockDocument(long documentId) {
        return jdbc.query("""
                SELECT title,original_filename,file_path FROM knowledge_document
                WHERE id=? AND NOT deleting FOR UPDATE
                """, (rs, row) -> new DocumentFile(rs.getString(1), rs.getString(2), rs.getString(3)), documentId)
                .stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    private List<Chapter> chapters(long documentId) {
        return jdbc.query("""
                SELECT id,COALESCE(pending_title,title),COALESCE(pending_content,content),enabled,index_status
                FROM rag_knowledge WHERE document_id=? ORDER BY id FOR UPDATE
                """, (rs, row) -> new Chapter(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getString(5)), documentId);
    }

    private List<KnowledgeDocumentTopics.Topic> replaceTopics(List<Chapter> previous,
            List<KnowledgeDocumentTopics.Topic> proposed, long documentId) {
        var matched = new HashSet<Long>();
        var result = new ArrayList<KnowledgeDocumentTopics.Topic>();
        boolean newEnabled = previous.stream().allMatch(Chapter::enabled);
        for (var topic : proposed) {
            var candidates = previous.stream().filter(old -> topic.id() == null ? old.title().equals(topic.title())
                    : old.id() == topic.id()).toList();
            if (candidates.size() > 1 || (topic.id() != null && candidates.isEmpty())) {
                throw KnowledgeDocException.conflict("KNOWLEDGE_CHAPTER_SCOPE", "章节标记或标题与所选文档不匹配");
            }
            if (candidates.isEmpty()) {
                long id = insertEntry(documentId, topic.title(), topic.content(), newEnabled);
                result.add(new KnowledgeDocumentTopics.Topic(id, topic.title(), topic.content()));
                continue;
            }
            Chapter old = candidates.getFirst();
            if (!matched.add(old.id()) || old.state().equals("DELETING")) {
                throw busy();
            }
            if (!old.title().equals(topic.title()) || !old.content().equals(topic.content())) {
                changeChapter(old, topic.title(), topic.content());
            }
            result.add(new KnowledgeDocumentTopics.Topic(old.id(), topic.title(), topic.content()));
        }
        if (matched.size() != previous.size()) {
            throw KnowledgeDocException.conflict("KNOWLEDGE_CHAPTER_MISSING", "新文件缺少原章节，请保留章节标记或单独处理该知识");
        }
        return result;
    }

    private void changeChapter(Chapter old, String title, String content) {
        if (!old.enabled() && old.state().equals("UNINDEXED")) {
            jdbc.update("UPDATE rag_knowledge SET title=?,content=? WHERE id=?", title, content, old.id());
        } else {
            setPending(old.id(), title, content);
        }
    }

    private void pointToFile(long id, String title, String filename, String path, String type, byte[] bytes) {
        // Keep prior originals as immutable evidence. Curated snapshots are reclaimed only after the pointer commits.
        if (jdbc.update("""
                UPDATE knowledge_document SET title=?,original_filename=?,
                    replaced_path=CASE WHEN file_path LIKE 'curated/%' AND file_path<>? THEN file_path ELSE replaced_path END,
                    file_path=?,file_type=?,file_size=?,file_sha256=? WHERE id=? AND (replaced_path IS NULL OR file_path=?)
                """, title, filename, path, path, type, bytes.length, KnowledgeFiles.sha256(bytes), id, path) == 0) {
            throw busy();
        }
    }

    public List<Entry> entries(int offset) {
        return entries(offset, null, "");
    }

    public List<Entry> entries(int offset, Long documentId, String query) {
        return jdbc.query("""
                SELECT k.id,k.document_id,k.title,d.original_filename,NULL AS content,k.enabled,k.index_status,k.updated_at,d.title
                FROM rag_knowledge k LEFT JOIN knowledge_document d ON d.id=k.document_id
                WHERE k.index_status<>'DELETING' AND (? IS NULL OR k.document_id=?)
                  AND (?='' OR LOCATE(?,k.title)>0 OR LOCATE(?,k.content)>0)
                ORDER BY k.updated_at DESC,k.id DESC LIMIT 51 OFFSET ?
                """, ENTRY, documentId, documentId, query, query, query, offset);
    }

    public KnowledgeList<Entry> entries(KnowledgeList.Filter filter, Long documentId, Boolean enabled) {
        String from = """
                 FROM (SELECT k.id,k.document_id,k.title,d.original_filename,NULL AS content,k.enabled,
                              k.index_status,k.updated_at,d.title AS module
                       FROM rag_knowledge k LEFT JOIN knowledge_document d ON d.id=k.document_id
                       WHERE k.index_status<>'DELETING' AND (? IS NULL OR k.document_id=?)
                         AND (LOCATE(?,k.title)>0 OR LOCATE(?,k.content)>0)
                         AND (? IS NULL OR k.enabled=?)
                         AND (? IS NULL OR k.updated_at>=?) AND (? IS NULL OR k.updated_at<=?)) k
                """;
        return KnowledgeList.query(jdbc, filter, from, "k.updated_at DESC,k.id DESC", ENTRY,
                documentId, documentId, filter.keyword(), filter.keyword(), enabled, enabled,
                filter.from(), filter.from(), filter.to(), filter.to());
    }

    public Entry entry(long id) {
        return jdbc.query("""
                SELECT k.id,k.document_id,k.title,d.original_filename,k.content,k.enabled,k.index_status,k.updated_at,d.title
                FROM rag_knowledge k LEFT JOIN knowledge_document d ON d.id=k.document_id
                WHERE k.id=? AND k.index_status<>'DELETING'
                """, ENTRY, id).stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    private static final RowMapper<Entry> ENTRY = (rs, row) -> new Entry(rs.getLong(1), rs.getObject(2, Long.class),
            rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7),
            rs.getObject(8, LocalDateTime.class), rs.getString(9));

    public long save(Long id, String title, String content) {
        String body = validatedContent(content);
        if (title.isBlank() || title.length() > 160 || title.chars().anyMatch(Character::isISOControl)) {
            throw KnowledgeDocException.bad("KNOWLEDGE_TITLE_INVALID", "请输入有效的知识标题");
        }
        return transaction.execute(status -> {
            if (id == null) {
                return insertEntry(null, title.strip(), body, true);
            }
            Entry entry = entry(id);
            if (entry.documentId() == null) {
                setPending(id, title.strip(), body);
                return id;
            }
            long documentId = entry.documentId();
            var document = lockDocument(documentId);
            var chapters = chapters(documentId);
            var chapter = chapters.stream().filter(row -> row.id() == id).findFirst()
                    .orElseThrow(KnowledgeDocException::missing);
            if (chapter.title().equals(title.strip()) && chapter.content().equals(body)) {
                return id;
            }
            changeChapter(chapter, title.strip(), body);
            if (KnowledgeFiles.isCurated(document.path())) {
                rewriteMaster(documentId, document);
            }
            return id;
        });
    }

    private void rewriteMaster(long documentId, DocumentFile document) {
        var topics = chapters(documentId).stream().filter(row -> !row.state().equals("DELETING"))
                .map(row -> new KnowledgeDocumentTopics.Topic(row.id(), row.title(), row.content())).toList();
        if (topics.isEmpty()) {
            throw KnowledgeDocException.conflict("KNOWLEDGE_DOCUMENT_EMPTY", "最后一章请通过删除整份文档处理");
        }
        byte[] master = KnowledgeDocumentTopics.render(document.title(), topics);
        pointToFile(documentId, document.title(), document.filename(), files.saveCurated(master), "MD", master);
    }

    public void retry(long id) {
        int changed = jdbc.update("""
                UPDATE rag_knowledge SET index_status='PENDING',index_attempts=0,retry_at=NULL
                WHERE id=? AND index_status IN ('FAILED','UNINDEXED')
                """, id);
        if (changed == 0) {
            throw busy();
        }
    }

    public void retryDocument(long id) {
        if (jdbc.update("""
                UPDATE rag_knowledge SET
                    retry_at=IF(index_status='READY',NOW(6),NULL),index_attempts=0,
                    index_status=IF(index_status='READY','READY','PENDING')
                WHERE document_id=? AND (index_status='FAILED' OR (enabled AND index_status='UNINDEXED')
                    OR (enabled AND index_status='READY' AND retry_at<=NOW(6) AND index_attempts>=3))
                """, id) == 0) {
            throw busy();
        }
    }

    public void setEnabled(long id, boolean enabled) {
        setEnabled(id, enabled, null);
    }

    public void setEnabled(long id, boolean enabled, LocalDateTime expectedUpdatedAt) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.update("""
                    UPDATE rag_knowledge SET index_status=IF(? AND index_status='UNINDEXED','PENDING',index_status),enabled=?
                    WHERE id=? AND index_status<>'DELETING'
                      AND (? IS NULL OR (updated_at=? AND (NOT ? OR index_status='READY')))
                    """, enabled, enabled, id, expectedUpdatedAt, expectedUpdatedAt, enabled) == 0) {
                throw expectedUpdatedAt == null ? KnowledgeDocException.missing() :
                        KnowledgeDocException.conflict("KNOWLEDGE_STALE", "知识已变更，请刷新列表后重试");
            }
            if (!enabled) {
                offlineWiki(id);
            }
        });
    }

    public void remove(long id, boolean document) {
        transaction.executeWithoutResult(status -> {
            Long documentId = document ? id : entry(id).documentId();
            var parent = documentId == null ? null : lockDocument(documentId);
            String column = document ? "document_id" : "id";
            List<Long> ids = jdbc.queryForList("SELECT id FROM rag_knowledge WHERE " + column + "=? FOR UPDATE", Long.class, id);
            for (Long knowledgeId : ids) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM wiki_page_source s JOIN wiki_page p ON p.id=s.wiki_page_id
                        WHERE s.knowledge_id=? AND (p.manually_edited OR NOT(p.auto_knowledge_id <=> s.knowledge_id)
                        OR EXISTS(SELECT 1 FROM wiki_page_source other WHERE other.wiki_page_id=p.id AND other.knowledge_id<>s.knowledge_id)))
                        """, Boolean.class, knowledgeId))) {
                    throw KnowledgeDocException.conflict("KNOWLEDGE_IN_USE", "知识仍被 Wiki 引用，请先处理来源关系");
                }
            }
            if ((document && jdbc.update("UPDATE knowledge_document SET deleting=TRUE WHERE id=?", id) == 0)
                    || (!document && ids.isEmpty())) {
                throw KnowledgeDocException.missing();
            }
            for (Long knowledgeId : ids) {
                jdbc.update("UPDATE rag_knowledge SET enabled=FALSE,index_status='DELETING',retry_at=NULL,index_attempts=0 WHERE id=?",
                        knowledgeId);
                requireWikiReview(knowledgeId);
            }
            if (document && ids.isEmpty()) {
                removeOriginal(id);
            }
            if (!document && parent != null && KnowledgeFiles.isCurated(parent.path())) {
                rewriteMaster(documentId, parent);
            }
        });
    }

    private long insertEntry(Long documentId, String title, String content, boolean enabled) {
        jdbc.update("INSERT INTO rag_knowledge(document_id,title,content,enabled,index_status) VALUES (?,?,?,?,?)",
                documentId, title, content, enabled, enabled ? "PENDING" : "UNINDEXED");
        return lastId();
    }

    private void setPending(long id, String title, String content) {
        if (jdbc.update("""
                UPDATE rag_knowledge SET pending_title=?,pending_content=?,index_status='PENDING',index_attempts=0,retry_at=NULL
                WHERE id=? AND index_status NOT IN ('PENDING','DELETING')
                """, title, content, id) == 0) {
            throw busy();
        }
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String validatedContent(String input) {
        String content = KnowledgeChunker.normalize(input);
        if (content.isEmpty() || content.length() > KnowledgeChunker.MAX_DOCUMENT_CHARS || content.indexOf('\0') >= 0) {
            throw KnowledgeDocException.bad("KNOWLEDGE_BODY_INVALID", "正文须为不超过两万字的非空文本");
        }
        return content;
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 3000, scheduler = "knowledgeScheduler")
    public void processPending() {
        withWorkerLock("knowledge-worker", this::processOne);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 3000, scheduler = "knowledgeScheduler")
    public void processWikiPending() {
        withWorkerLock("knowledge-wiki-worker", this::processOneWiki);
    }

    private void withWorkerLock(String worker, Runnable task) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "SELECT GET_LOCK(CONCAT(DATABASE(),?),0)", ":" + worker)) {
                return null;
            }
            try {
                task.run();
            } finally {
                lock(connection, "SELECT RELEASE_LOCK(CONCAT(DATABASE(),?))", ":" + worker);
            }
            return null;
        });
    }

    private static boolean lock(Connection connection, String sql, String name) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == 1;
            }
        }
    }

    private record Work(long id, Long documentId, long revision, String title, String content, String status, int attempts,
                        boolean editing) {}

    private void processOne() {
        jdbc.update("""
                UPDATE rag_knowledge SET index_status='FAILED'
                WHERE index_status='PENDING' AND index_attempts>=3 AND retry_at<=NOW(6)
                """);
        List<Work> jobs = jdbc.query("""
                SELECT id,document_id,revision,COALESCE(pending_title,title),COALESCE(pending_content,content),index_status,
                       index_attempts,pending_content IS NOT NULL
                FROM rag_knowledge WHERE (index_status='DELETING' OR (index_status='PENDING' AND index_attempts<3))
                  AND (retry_at IS NULL OR retry_at<=NOW(6)) ORDER BY COALESCE(retry_at,updated_at),id LIMIT 1
                """, (rs, row) -> new Work(rs.getLong(1), rs.getObject(2, Long.class), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getBoolean(8)));
        if (jobs.isEmpty()) {
            cleanupOldChunks();
            cleanupReplacedOriginal();
            return;
        }
        Work job = jobs.getFirst();
        int attempt = job.attempts() + 1;
        if (jdbc.update("""
                UPDATE rag_knowledge SET index_attempts=LEAST(255,index_attempts+1),retry_at=TIMESTAMPADD(SECOND,?,NOW(6))
                WHERE id=? AND index_status=? AND index_attempts=?
                """, job.status().equals("DELETING") ? 120 : 30 * attempt, job.id(), job.status(), job.attempts()) == 0) {
            return;
        }
        try {
            if ("DELETING".equals(job.status())) {
                delete(job);
            } else {
                index(job);
            }
        } catch (RuntimeException failure) {
            if (attempt >= 3 && job.status().equals("PENDING")) {
                jdbc.update("UPDATE rag_knowledge SET index_status='FAILED' WHERE id=? AND index_status='PENDING'", job.id());
            }
            log.warn("知识处理等待恢复，id={}，原因={}", job.id(),
                    failure instanceof KnowledgeDocException error ? error.code() : failure.getClass().getSimpleName());
        }
    }

    private void index(Work job) {
        long started = System.nanoTime();
        long revision = job.revision() + 1;
        List<KnowledgeChunker.Chunk> chunks = KnowledgeChunker.chunk("doc-" + job.id(), Long.toString(revision), job.content());
        List<String> ids = chunks.stream().map(chunk -> chunk.pointId().toString()).toList();
        List<String> staged = jdbc.queryForList("""
                SELECT qdrant_point_id FROM rag_chunk WHERE knowledge_id=? AND revision=? ORDER BY chunk_no
                """, String.class, job.id(), revision);
        if (!staged.equals(ids)) {
            vectors.delete(staged);
            transaction.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM rag_chunk WHERE knowledge_id=? AND revision=?", job.id(), revision);
                jdbc.batchUpdate("INSERT INTO rag_chunk(knowledge_id,revision,chunk_no,content,qdrant_point_id) VALUES (?,?,?,?,?)",
                        chunks.stream().map(chunk -> new Object[]{job.id(), revision, chunk.ordinal(), chunk.text(), chunk.pointId().toString()}).toList());
            });
        }
        long vectorStarted = System.nanoTime();
        boolean firstIndex = Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM rag_chunk c JOIN rag_knowledge k
                              ON c.knowledge_id=k.id AND c.revision=k.revision)
                """, Boolean.class));
        vectors.prepare(job.id(), revision, job.title(), job.content(), chunks, firstIndex);
        log.info("knowledge stage=rag_vectors id={} chunks={} millis={}", job.id(), chunks.size(), elapsed(vectorStarted));
        long committed = System.nanoTime();
        transaction.executeWithoutResult(status -> {
            // READY 对应已提交的可检索向量；非空 retry_at 表示该版本仍有独立 Wiki 工作。
            if (jdbc.update("""
                    UPDATE rag_knowledge SET title=?,content=?,revision=?,index_status='READY',index_attempts=0,retry_at=NOW(6),
                                             pending_title=NULL,pending_content=NULL WHERE id=? AND index_status='PENDING'
                    """, job.title(), job.content(), revision, job.id()) == 0) {
                return;
            }
            if (job.editing()) {
                requireWikiReview(job.id());
            }
            if (!wikiEligible(job.id())) {
                clearWikiWork(job.id(), revision);
            }
        });
        log.info("knowledge stage=rag_ready id={} db_millis={} millis={}", job.id(), elapsed(committed), elapsed(started));
    }

    private boolean wikiEligible(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT enabled AND NOT EXISTS(
                    SELECT 1 FROM wiki_page_source ref JOIN wiki_page page ON page.id=ref.wiki_page_id
                    WHERE ref.knowledge_id=k.id AND page.manually_edited
                ) FROM rag_knowledge k WHERE k.id=?
                """, Boolean.class, id));
    }

    private void clearWikiWork(long id, long revision) {
        jdbc.update("""
                UPDATE rag_knowledge SET index_attempts=0,retry_at=NULL
                WHERE id=? AND revision=? AND index_status='READY'
                """, id, revision);
    }

    private record WikiWork(long id, long revision, String title, String content, int attempts) {}

    private void processOneWiki() {
        var jobs = jdbc.query("""
                SELECT id,revision,title,content,index_attempts FROM rag_knowledge
                WHERE enabled AND index_status='READY' AND retry_at<=NOW(6) AND index_attempts<3
                ORDER BY retry_at,id LIMIT 1
                """, (rs, row) -> new WikiWork(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
        if (jobs.isEmpty()) {
            return;
        }
        WikiWork job = jobs.getFirst();
        int attempt = job.attempts() + 1;
        // 先写入重试时间，进程中断后也能接续同一版本。
        if (jdbc.update("""
                UPDATE rag_knowledge SET index_attempts=?,retry_at=TIMESTAMPADD(SECOND,120,NOW(6))
                WHERE id=? AND revision=? AND index_status='READY' AND enabled AND index_attempts=? AND retry_at<=NOW(6)
                """, attempt, job.id(), job.revision(), job.attempts()) == 0) {
            return;
        }
        long started = System.nanoTime();
        try {
            var draft = wikiEligible(job.id()) ? wiki.generate(List.of(new KnowledgeWikiGenerator.Source(job.title(), job.content()))) : null;
            long generated = elapsed(started);
            transaction.executeWithoutResult(status -> {
                var current = jdbc.queryForList("""
                        SELECT id FROM rag_knowledge WHERE id=? AND revision=? AND index_status='READY' AND enabled
                          AND retry_at IS NOT NULL AND index_attempts=? FOR UPDATE
                        """, Long.class, job.id(), job.revision(), attempt);
                if (!current.isEmpty()) {
                    if (draft != null && wikiEligible(job.id())) {
                        publishAutomatic(job.id(), draft);
                    }
                    clearWikiWork(job.id(), job.revision());
                }
            });
            log.info("knowledge stage=wiki id={} attempt={} generate_millis={} millis={}",
                    job.id(), attempt, generated, elapsed(started));
        } catch (RuntimeException failure) {
            jdbc.update("""
                    UPDATE rag_knowledge SET retry_at=TIMESTAMPADD(SECOND,?,NOW(6))
                    WHERE id=? AND revision=? AND index_status='READY' AND index_attempts=? AND retry_at IS NOT NULL
                    """, attempt >= 3 ? 0 : 10 * attempt, job.id(), job.revision(), attempt);
            log.warn("knowledge stage=wiki id={} attempt={} millis={} reason={}", job.id(), attempt, elapsed(started),
                    failure instanceof KnowledgeDocException error ? error.code() : failure.getClass().getSimpleName());
        }
    }

    private void publishAutomatic(long knowledgeId, KnowledgeWikiGenerator.Draft draft) {
        List<Long> pages = jdbc.queryForList("SELECT id FROM wiki_page WHERE auto_knowledge_id=? AND NOT manually_edited FOR UPDATE",
                Long.class, knowledgeId);
        if (pages.isEmpty()) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM wiki_page WHERE auto_knowledge_id=?)",
                    Boolean.class, knowledgeId))) {
                return;
            }
            jdbc.update("INSERT INTO wiki_page(title,summary,content,status,auto_knowledge_id,published_at) VALUES (?,?,?,'PUBLISHED',?,NOW(6))",
                    draft.title(), draft.summary(), draft.content(), knowledgeId);
            jdbc.update("INSERT INTO wiki_page_source(wiki_page_id,knowledge_id) VALUES (?,?)", lastId(), knowledgeId);
        } else {
            KnowledgePages.snapshot(jdbc, pages.getFirst());
            jdbc.update("UPDATE wiki_page SET title=?,summary=?,content=?,status='PUBLISHED',review_required=FALSE,published_at=NOW(6) WHERE id=? AND NOT manually_edited",
                    draft.title(), draft.summary(), draft.content(), pages.getFirst());
        }
    }

    private void offlineWiki(long knowledgeId) {
        jdbc.update("""
                UPDATE wiki_page p JOIN wiki_page_source s ON p.id=s.wiki_page_id
                SET p.status='OFFLINE' WHERE s.knowledge_id=? AND p.status='PUBLISHED'
                """, knowledgeId);
    }

    private void requireWikiReview(long knowledgeId) {
        jdbc.update("""
                UPDATE wiki_page p JOIN wiki_page_source s ON p.id=s.wiki_page_id
                SET p.status=IF(p.status='PUBLISHED','OFFLINE',p.status),p.review_required=TRUE
                WHERE s.knowledge_id=?
                """, knowledgeId);
    }

    private void delete(Work job) {
        List<String> points = jdbc.queryForList("SELECT qdrant_point_id FROM rag_chunk WHERE knowledge_id=?", String.class, job.id());
        vectors.delete(points);
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM wiki_page_source WHERE knowledge_id=?", job.id());
            jdbc.update("DELETE FROM rag_chunk WHERE knowledge_id=?", job.id());
            jdbc.update("DELETE FROM rag_knowledge WHERE id=? AND index_status='DELETING'", job.id());
            if (job.documentId() != null) {
                removeOriginal(job.documentId());
            }
        });
    }

    private void removeOriginal(long id) {
        jdbc.query("""
                SELECT file_path,replaced_path FROM knowledge_document d WHERE id=? AND deleting
                  AND NOT EXISTS(SELECT 1 FROM rag_knowledge k WHERE k.document_id=d.id) FOR UPDATE
                """, rs -> {
            removeUnreferenced(rs.getString(2));
            if (rs.getString(1) != null) {
                files.delete(rs.getString(1));
            }
            jdbc.update("DELETE FROM knowledge_document WHERE id=?", id);
        }, id);
    }

    private void cleanupReplacedOriginal() {
        transaction.executeWithoutResult(status -> jdbc.query("""
                SELECT id,replaced_path FROM knowledge_document WHERE replaced_path IS NOT NULL LIMIT 1 FOR UPDATE
                """, rs -> {
            removeUnreferenced(rs.getString(2));
            jdbc.update("UPDATE knowledge_document SET replaced_path=NULL WHERE id=?", rs.getLong(1));
        }));
    }

    private void removeUnreferenced(String path) {
        if (path != null && jdbc.queryForList("SELECT id FROM knowledge_document WHERE file_path=? FOR UPDATE", Long.class, path).isEmpty()) {
            files.delete(path);
        }
    }

    private void cleanupOldChunks() {
        List<String> points = jdbc.queryForList("""
                SELECT c.qdrant_point_id FROM rag_chunk c JOIN rag_knowledge k ON k.id=c.knowledge_id
                WHERE c.revision<k.revision LIMIT 128
                """, String.class);
        if (!points.isEmpty()) {
            vectors.delete(points);
            jdbc.batchUpdate("DELETE FROM rag_chunk WHERE qdrant_point_id=?",
                    points.stream().map(id -> new Object[]{id}).toList());
        }
    }

    private static KnowledgeDocException busy() {
        return KnowledgeDocException.conflict("KNOWLEDGE_BUSY", "知识正在处理或已删除，请刷新列表");
    }
}
