package com.zcx.chenstack.service;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.rag.*;
import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.utils.SecurityUtils;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 复用正式知识目录和检索链，不把数据库行或索引参数暴露为管理契约。 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "chen.knowledge.simple", name = "enabled", havingValue = "false", matchIfMissing = true)
@lombok.extern.slf4j.Slf4j
public class AiKnowledgeManagementService {
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Document(String sourceId, String title, String text, String version, String sourceType,
                           String sourceUrl, String visibility, String reviewStatus, Instant updatedAt,
                           List<String> keywords, String contentHash, long generation, boolean effective) {}
    public record Draft(String draftId, String sourceId, String title, String text, String version, String sourceUrl,
                        String sourceUrlLabel, List<String> keywords, String state, String updatedAt, String operator,
                        String sourceRef) {}
    public record Version(String version, String title, String text, String sourceUrl, String sourceUrlLabel,
                          List<String> keywords, String contentHash, String state, String archivedAt, String operator) {}
    public record Status(String mode, List<String> supportedModes, Map<String, Object> rag,
                         int documentCount, long generation, Map<String, Boolean> capabilities) {}
    public record Hit(String id, String title, String text, String sourceUrl, String version) {}
    public record Search(String mode, List<Hit> items, int total) {}
    public record PendingOperation(String operationId, String sourceId, String title, String action, String status, long generation) {}
    private static final Map<String, String> SOURCE_URL_LABELS = Map.ofEntries(
            Map.entry("/", "首页"), Map.entry("/register", "注册页"), Map.entry("/login", "登录页"),
            Map.entry("/reset", "找回密码页"), Map.entry("/search", "站内搜索"), Map.entry("/editor", "文章编辑器"),
            Map.entry("/creation/articlemanage", "文章管理"), Map.entry("/creation/columnmanage", "专栏管理"),
            Map.entry("/article", "文章详情"), Map.entry("/setting", "个人设置"));
    private final CatalogLifecycle lifecycle;
    private final RagKnowledgeService rag;
    private final AiKnowledgeProperties properties;
    private final AdminSecurity security;
    private final ObjectProvider<WikiPublicationService> wiki;
    private final JdbcTemplate jdbc;
    private final RagKnowledgeAdministration administration;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zcx.chenstack.knowledge.KnowledgeDocRetrievalService docRetrieval;

    public AiKnowledgeManagementService(CatalogLifecycle lifecycle, RagKnowledgeService rag,
            AiKnowledgeProperties properties, AdminSecurity security, ObjectProvider<WikiPublicationService> wiki) {
        this(lifecycle, rag, properties, security, wiki, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiKnowledgeManagementService(CatalogLifecycle lifecycle, RagKnowledgeService rag,
            AiKnowledgeProperties properties, AdminSecurity security, ObjectProvider<WikiPublicationService> wiki,
            JdbcTemplate jdbc) {
        this.lifecycle = lifecycle; this.rag = rag; this.properties = properties; this.security = security; this.wiki = wiki;
        this.jdbc = jdbc;
        this.administration = new RagKnowledgeAdministration(lifecycle, rag);
    }


    public Status status() {
        Map<String, Boolean> capabilities = capabilities();
        if (capabilities.values().stream().noneMatch(Boolean::booleanValue)) throw denied();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        return new Status(properties.knowledgeMode(), List.of("chat", "rag", "wiki"), rag.diagnostics(),
                catalog.sourceIds().size(), catalog.epoch(), capabilities);
    }

    public Page<Document> documents(String query, int page, int size) {
        require("ai:rag:read");
        if (query == null || query.length() > 80 || page < 1 || page > 10000 || size < 1 || size > 50) throw bad();
        KnowledgeCatalog catalog = lifecycle.snapshot();
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<KnowledgeDocument> found = catalog.sourceIds().stream().map(catalog::source)
                .filter(source -> q.isEmpty() || source.title().toLowerCase(Locale.ROOT).contains(q)
                        || source.sourceId().contains(q)).sorted(Comparator.comparing(KnowledgeDocument::title)).toList();
        List<Document> items = found.stream().skip((long) (page - 1) * size).limit(size).map(source -> view(source, catalog)).toList();
        catalog.requireCurrent();
        return new Page<>(items, found.size(), page, size);
    }

    public Document document(String id) {
        require("ai:rag:read");
        KnowledgeCatalog catalog = lifecycle.snapshot();
        KnowledgeDocument source = source(catalog, id);
        return view(source, catalog);
    }

    public RagKnowledgeAdministration.Result update(String id, String title, String text, String version, List<String> keywords,
            long expectedGeneration, String expectedContentHash, String requestId) {
        require("ai:rag:manage");
        int actor = SecurityUtils.getUserId();
        KnowledgeDocument previous = source(lifecycle.snapshot(), id);
        KnowledgeDocument replacement = new KnowledgeDocument(previous.sourceId(), title, text, version, previous.sourceType(),
                previous.sourceUrl(), previous.visibility(), previous.reviewStatus(), Instant.now(), keywords);
        beginArchive(previous, actor);
        try {
            RagKnowledgeAdministration.Result result = administration.update(replacement, expectedGeneration, expectedContentHash, requestId, actor);
            finishArchive(previous, result.generation(), "SUPERSEDED");
            return result;
        } catch (RuntimeException failure) {
            settleArchive(previous, "SUPERSEDED");
            throw failure;
        }
    }

    public RagKnowledgeAdministration.Result revoke(String id, long expectedGeneration, String expectedContentHash, String requestId) {
        require("ai:rag:manage");
        int actor = SecurityUtils.getUserId();
        KnowledgeDocument previous = source(lifecycle.snapshot(), id);
        beginArchive(previous, actor);
        try {
            RagKnowledgeAdministration.Result result = administration.revoke(id, expectedGeneration, expectedContentHash, requestId, actor);
            finishArchive(previous, result.generation(), "REVOKED");
            return result;
        } catch (RuntimeException failure) {
            settleArchive(previous, "REVOKED");
            throw failure;
        }
    }

    /** 人工新增知识的草稿箱；只有管理员可见，不进入检索和索引。 */
    public List<Draft> drafts(String query) {
        require("ai:rag:manage");
        requireStorage();
        String q = query == null ? "" : query.strip();
        if (q.length() > 80) throw bad();
        return jdbc.query("""
                SELECT d.id,d.source_key,d.title,v.normalized_body,v.version_label,d.source_url,
                       d.keywords_json,d.review_status,d.updated_by,d.updated_at,d.origin_ref
                FROM knowledge_document d
                JOIN knowledge_document_version v ON v.document_id=d.id AND v.state='DRAFT'
                WHERE d.review_status='DRAFT' AND d.document_kind='PLATFORM_HELP'
                  AND d.deleted=FALSE AND (?='' OR LOCATE(?,d.title)>0)
                ORDER BY d.updated_at DESC,d.id DESC LIMIT 100
                """, (rs, index) -> draft(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                time(rs.getTimestamp(10)), nickname(rs.getInt(9)), rs.getString(11)), q, q);
    }

    public Draft createDraft(String title, String text, String version, String sourceUrl, List<String> keywords) {
        require("ai:rag:manage");
        requireStorage();
        DraftInput input = validateDraft(title, text, version, sourceUrl, keywords);
        String sourceId = "help-managed-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int actor = SecurityUtils.getUserId();
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO knowledge_document(source_key,document_kind,mirror_mode,source_ref_kind,source_url,
                        origin_ref,title,visibility,review_status,publish_status,keywords_json,created_by,
                        created_by_kind,updated_by,updated_by_kind)
                    VALUES(?,'PLATFORM_HELP','AUTHORITATIVE',?,?,'admin-draft',?,'INTERNAL',
                        'DRAFT','UNPUBLISHED',?,?,'USER',?,'USER')
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, sourceId);
            insert.setString(2, input.sourceRefKind());
            insert.setString(3, input.sourceUrl());
            insert.setString(4, input.title());
            insert.setString(5, JSON.toJSONString(input.keywords()));
            insert.setInt(6, actor);
            insert.setInt(7, actor);
            return insert;
        }, keys);
        long documentId = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,normalized_body,
                    raw_format,normalized_sha256,char_length,body_available,body_provenance,state,created_by,created_by_kind)
                VALUES(?,1,?,?,?,'TEXT',?,?,TRUE,'IMPORT','DRAFT',?,'USER')
                """, documentId, input.version(), input.text(), input.text(),
                com.zcx.chenstack.knowledge.KnowledgeChunker.contentHash(input.text()),
                input.text().length(), actor);
        return draftById(Long.toString(documentId));
    }

    public Draft updateDraft(String id, String title, String text, String version, String sourceUrl, List<String> keywords) {
        require("ai:rag:manage");
        requireStorage();
        DraftInput input = validateDraft(title, text, version, sourceUrl, keywords);
        int actor = SecurityUtils.getUserId();
        long documentId;
        try {
            documentId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new WikiProductException(404, "知识草稿不存在或已发布");
        }
        int versions = jdbc.update("""
                UPDATE knowledge_document_version
                SET raw_body=?,normalized_body=?,version_label=?,normalized_sha256=?,char_length=?
                WHERE document_id=? AND state='DRAFT'
                """, input.text(), input.text(), input.version(),
                com.zcx.chenstack.knowledge.KnowledgeChunker.contentHash(input.text()),
                input.text().length(), documentId);
        int documents = jdbc.update("""
                UPDATE knowledge_document SET title=?,source_ref_kind=?,source_url=?,keywords_json=?,updated_by=?
                WHERE id=? AND review_status='DRAFT' AND document_kind='PLATFORM_HELP' AND deleted=FALSE
                """, input.title(), input.sourceRefKind(), input.sourceUrl(),
                JSON.toJSONString(input.keywords()), actor, documentId);
        if (versions != 1 || documents != 1) throw new WikiProductException(404, "知识草稿不存在或已发布");
        return draftById(id);
    }

    public void discardDraft(String id) {
        require("ai:rag:manage");
        requireStorage();
        long documentId;
        try {
            documentId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new WikiProductException(404, "知识草稿不存在或已发布");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.source_key,d.review_status FROM knowledge_document d
                WHERE d.id=? AND d.document_kind='PLATFORM_HELP' AND d.deleted=FALSE
                """, documentId);
        if (rows.isEmpty() || !"DRAFT".equals(rows.getFirst().get("review_status"))) {
            throw new WikiProductException(404, "知识草稿不存在或已发布");
        }
        String sourceId = String.valueOf(rows.getFirst().get("source_key"));
        if (lifecycle.snapshot().source(sourceId) != null) {
            throw new WikiProductException(409, "该知识已发布，请刷新列表，不能按草稿删除");
        }
        jdbc.update("DELETE FROM knowledge_document_version WHERE document_id=? AND state='DRAFT'", documentId);
        jdbc.update("DELETE FROM knowledge_document WHERE id=? AND review_status='DRAFT'", documentId);
    }

    /**
     * 把一条 AI 候选知识保存为原有知识草稿，并保留来源导入任务、原始文档和版本。
     *
     * <p>{@code originRef} 同时承担幂等键：同一导入任务、同一条候选重复提交时复用原草稿，
     * 不再生成第二份。只有管理员确认后的候选才会走这里，不会自动审核或发布。</p>
     */
    @org.springframework.transaction.annotation.Transactional
    public Draft createDraftFromImport(String importRequestId, String title, String text, String version,
                                       String sourceUrl, List<String> keywords, String originRef,
                                       long sourceDocumentId, long sourceVersionId) {
        require("ai:rag:manage");
        requireStorage();
        if (importRequestId == null || !importRequestId.matches("[A-Za-z0-9._-]{1,72}")
                || originRef == null || !originRef.matches("imp:[A-Za-z0-9._-]{1,72}:[a-f0-9]{16}")) {
            throw bad();
        }
        DraftInput input = validateDraft(title, text, version, sourceUrl, keywords);
        int actor = SecurityUtils.getUserId();
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id,review_status FROM knowledge_document
                WHERE origin_ref=? AND document_kind='PLATFORM_HELP' AND deleted=FALSE
                ORDER BY id LIMIT 1
                """, originRef);
        if (!existing.isEmpty()) {
            long documentId = ((Number) existing.getFirst().get("id")).longValue();
            if (!"DRAFT".equals(existing.getFirst().get("review_status"))) {
                throw new WikiProductException(409, "该候选知识已存在且不再是草稿，未重复创建");
            }
            jdbc.update("""
                    UPDATE knowledge_document_version
                    SET raw_body=?,normalized_body=?,version_label=?,normalized_sha256=?,char_length=?
                    WHERE document_id=? AND state='DRAFT'
                    """, input.text(), input.text(), input.version(),
                    com.zcx.chenstack.knowledge.KnowledgeChunker.contentHash(input.text()),
                    input.text().length(), documentId);
            jdbc.update("""
                    UPDATE knowledge_document SET title=?,source_ref_kind=?,source_url=?,keywords_json=?,updated_by=?
                    WHERE id=? AND review_status='DRAFT' AND document_kind='PLATFORM_HELP' AND deleted=FALSE
                    """, input.title(), input.sourceRefKind(), input.sourceUrl(),
                    JSON.toJSONString(input.keywords()), actor, documentId);
            return draftById(Long.toString(documentId));
        }
        String sourceId = "help-managed-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO knowledge_document(source_key,document_kind,mirror_mode,source_ref_kind,source_url,
                        origin_ref,title,visibility,review_status,publish_status,keywords_json,created_by,
                        created_by_kind,updated_by,updated_by_kind)
                    VALUES(?,'PLATFORM_HELP','AUTHORITATIVE',?,?,?,?,'INTERNAL','DRAFT','UNPUBLISHED',?,?,'USER',?,'USER')
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            insert.setString(1, sourceId);
            insert.setString(2, input.sourceRefKind());
            insert.setString(3, input.sourceUrl());
            insert.setString(4, originRef);
            insert.setString(5, input.title());
            insert.setString(6, JSON.toJSONString(input.keywords()));
            insert.setInt(7, actor);
            insert.setInt(8, actor);
            return insert;
        }, keys);
        long documentId = keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,normalized_body,
                    raw_format,normalized_sha256,char_length,body_available,body_provenance,state,created_by,created_by_kind)
                VALUES(?,1,?,?,?,'TEXT',?,?,TRUE,'IMPORT','DRAFT',?,'USER')
                """, documentId, input.version(), input.text(), input.text(),
                com.zcx.chenstack.knowledge.KnowledgeChunker.contentHash(input.text()),
                input.text().length(), actor);
        Long versionId = jdbc.queryForObject("""
                SELECT id FROM knowledge_document_version WHERE document_id=? AND state='DRAFT'
                """, Long.class, documentId);
        try {
            storeAuditDraftCreated(importRequestId, documentId, versionId, originRef,
                    sourceDocumentId, sourceVersionId, actor);
        } catch (RuntimeException auditFailure) {
            // 审计失败不能把已经保存的草稿伪装成失败；下次保存会按 originRef 复用并补记。
            log.warn("AI候选知识草稿审计写入失败，原因类型={}", auditFailure.getClass().getSimpleName());
        }
        return draftById(Long.toString(documentId));
    }

    private void storeAuditDraftCreated(String importRequestId, long documentId, Long versionId, String originRef,
                                        long sourceDocumentId, long sourceVersionId, int actor) {
        jdbc.update("""
                INSERT INTO knowledge_audit_log(request_id,document_id,version_id,import_task_id,actor_id,actor_kind,
                    action,target_kind,reason_code,detail_json)
                VALUES(?,?,?,?,?,'USER','CREATE','DOCUMENT','AI_CANDIDATE_DRAFT',?)
                """, importRequestId, documentId, versionId, importRequestId, actor,
                JSON.toJSONString(Map.of("originRef", originRef,
                        "sourceDocumentId", sourceDocumentId, "sourceVersionId", sourceVersionId)));
    }

    /**
     * 草稿发布走与更新/撤销相同的知识目录、索引和操作回执链。
     * 同一 requestId 重试会复用已完成的目录操作，不会重复写入。
     */
    public RagKnowledgeAdministration.Result publishDraft(String id, String requestId, long expectedGeneration,
                                                          boolean confirmReviewed) {
        require("ai:rag:manage");
        if (!confirmReviewed) throw new WikiProductException(400, "请先预览正文并确认已审核");
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,72}")) throw bad();
        requireStorage();
        long documentId;
        try {
            documentId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new WikiProductException(404, "知识草稿不存在");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.source_key,d.title,d.review_status,d.publish_status,
                       v.normalized_body,v.version_label,d.source_url,d.keywords_json
                FROM knowledge_document d
                JOIN knowledge_document_version v ON v.document_id=d.id AND v.state='DRAFT'
                WHERE d.id=? AND d.document_kind='PLATFORM_HELP' AND d.deleted=FALSE
                """, documentId);
        if (rows.isEmpty()) throw new WikiProductException(404, "知识草稿不存在");
        Map<String, Object> row = rows.getFirst();
        String sourceId = String.valueOf(row.get("source_key"));
        if ("PUBLISHED".equals(row.get("publish_status"))) {
            RagKnowledgeAdministration.Result replay = administration.completedCreate(requestId, sourceId);
            if (replay != null) {
                return replay;
            }
            throw new WikiProductException(409, "该知识已经发布，请刷新列表");
        }
        // 客户端未收到已成功操作的响应时，按同一幂等键补记完成，不重复写目录或索引。
        RagKnowledgeAdministration.Result completed = administration.completedCreate(requestId, sourceId);
        if (completed != null) {
            markDraftPublished(documentId, requestId, completed.generation());
            return completed;
        }
        if (lifecycle.snapshot().source(sourceId) != null) {
            throw new WikiProductException(409, "该知识已经发布，请刷新列表");
        }
        Object sourceUrlValue = row.get("source_url");
        String sourceUrl = publishedSourceUrl(sourceUrlValue);
        if (!KnowledgeCatalog.validSourceUrl(sourceUrl)) {
            throw new WikiProductException(400, "发布前请先编辑草稿并选择用途页面");
        }
        String keywordsJson = row.get("keywords_json") == null ? null : String.valueOf(row.get("keywords_json"));
        List<String> keywords = keywordsJson == null || keywordsJson.isBlank()
                ? List.of() : JSON.parseArray(keywordsJson, String.class);
        if (keywords == null) keywords = List.of();
        String body = String.valueOf(row.get("normalized_body"));
        KnowledgeDocument addition = new KnowledgeDocument(sourceId, String.valueOf(row.get("title")),
                body, String.valueOf(row.get("version_label")), "PLATFORM_HELP",
                sourceUrl, "PUBLIC", "DOCUMENT_REVIEWED", Instant.now(), keywords);
        RagKnowledgeAdministration.Result result = administration.create(addition, expectedGeneration, requestId,
                SecurityUtils.getUserId());
        if ("COMPLETED".equals(result.status())) {
            markDraftPublished(documentId, requestId, result.generation());
        }
        return result;
    }

    private void markDraftPublished(long documentId, String requestId, long generation) {
        int actor = SecurityUtils.getUserId();
        jdbc.update("""
                UPDATE knowledge_document_version SET state='CURRENT',published_at=CURRENT_TIMESTAMP(6)
                WHERE document_id=? AND state='DRAFT'
                """, documentId);
        List<Map<String, Object>> version = jdbc.queryForList("""
                SELECT id,version_no,normalized_sha256 FROM knowledge_document_version
                WHERE document_id=? AND state='CURRENT' ORDER BY version_no DESC LIMIT 1
                """, documentId);
        if (version.isEmpty()) return;
        Map<String, Object> current = version.getFirst();
        jdbc.update("""
                UPDATE knowledge_document SET review_status='APPROVED',publish_status='PUBLISHED',
                    current_published_version_id=?,current_version_no=?,content_sha256=?,updated_by=?
                WHERE id=?
                """, ((Number) current.get("id")).longValue(), ((Number) current.get("version_no")).intValue(),
                String.valueOf(current.get("normalized_sha256")), actor, documentId);
        jdbc.update("UPDATE rag_catalog_state SET generation=GREATEST(generation,?) WHERE id=1", generation);
    }

    /** 仅返回被替换/撤销的历史正文；当前正文以知识目录为准。 */
    public List<Version> versions(String sourceId) {
        require("ai:rag:read");
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}")) throw bad();
        source(lifecycle.snapshot(), sourceId);
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT v.version_label,d.title,v.normalized_body,d.source_url,v.normalized_sha256,
                       v.state,v.created_by,v.created_at
                FROM knowledge_document_version v
                JOIN knowledge_document d ON d.id=v.document_id
                WHERE d.source_key=? AND v.state<>'DRAFT'
                ORDER BY v.version_no DESC LIMIT 50
                """, (rs, index) -> new Version(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                sourceUrlLabel(rs.getString(4)),
                java.util.List.of(), rs.getString(5), rs.getString(6),
                time(rs.getTimestamp(8)), nickname(rs.getInt(7))), sourceId);
    }

    public List<PendingOperation> pendingOperations() {
        require("ai:rag:manage");
        KnowledgeCatalog catalog = lifecycle.snapshot();
        return administration.pendingOperations().stream().map(operation -> {
            KnowledgeDocument source = catalog.source(operation.sourceId());
            return new PendingOperation(operation.operationId(), operation.sourceId(), source == null ? "知识同步" : source.title(),
                    operation.action(), operation.status(), operation.generation());
        }).toList();
    }

    public RagKnowledgeAdministration.Result resume(String operationId) {
        require("ai:rag:manage");
        return administration.resume(operationId, SecurityUtils.getUserId());
    }

    public Search test(String query, String mode) {
        require("ai:knowledge:test");
        if (query == null || query.isBlank() || query.length() > 500) throw bad();
        List<Hit> items;
        if ("rag".equals(mode)) {
            KnowledgeEvidence evidence = rag.retrieve(query);
            List<String> ids = evidence.chunks().stream().map(chunk -> (String) chunk.getMetadata().get("source_id")).distinct().toList();
            items = ids.stream().map(id -> evidence.catalog().source(id))
                    .map(source -> new Hit(source.sourceId(), source.title(), evidence.chunks().stream()
                            .filter(chunk -> source.sourceId().equals(chunk.getMetadata().get("source_id")))
                            .map(chunk -> chunk.getText()).collect(java.util.stream.Collectors.joining("\n\n")),
                            source.sourceUrl(), source.version())).toList();
            evidence.catalog().requireCurrent();
        } else if ("doc".equals(mode)) {
            if (docRetrieval == null) throw new WikiProductException(503, "文档通道未启用");
            var evidence = docRetrieval.retrieve(query);
            items = evidence.hits().stream()
                    .map(hit -> new Hit(hit.sourceKey(), hit.title(), hit.text(), null, hit.versionLabel()))
                    .toList();
        } else if ("wiki".equals(mode)) {
            WikiPublicationService publication = wiki.getIfAvailable();
            if (publication == null) throw new WikiProductException(503, "整理知识服务尚未启用");
            if (query.length() > 80) throw new WikiProductException(400, "整理知识检索问题最多80个字");
            items = publication.list(query, null, 1, 12).items().stream().map(card -> publication.read(card.id()))
                    .map(page -> new Hit(Long.toString(page.id()), page.title(), page.markdown(), null, Integer.toString(page.version()))).toList();
        } else throw bad();
        return new Search(mode, items, items.size());
    }

    public Map<String, Boolean> capabilities() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        result.put("ragRead", security.hasKnowledgePermission("ai:rag:read"));
        result.put("ragManage", security.hasKnowledgePermission("ai:rag:manage"));
        result.put("wikiRead", security.hasKnowledgePermission("ai:wiki:read"));
        result.put("wikiSourceManage", security.hasKnowledgePermission("ai:source:manage"));
        result.put("wikiCompile", security.hasKnowledgePermission("ai:wiki:compile"));
        result.put("wikiEdit", security.hasKnowledgePermission("ai:wiki:edit"));
        result.put("wikiReview", security.hasKnowledgePermission("ai:wiki:review"));
        result.put("wikiPublish", security.hasKnowledgePermission("ai:wiki:publish"));
        result.put("test", security.hasKnowledgePermission("ai:knowledge:test"));
        return Collections.unmodifiableMap(result);
    }

    private static Document view(KnowledgeDocument source, KnowledgeCatalog catalog) {
        return new Document(source.sourceId(), source.title(), source.text(), source.version(), source.sourceType(), source.sourceUrl(),
                source.visibility(), source.reviewStatus(), source.updatedAt(), source.keywords(),
                KnowledgeCatalog.hash(KnowledgeCatalog.normalize(source.text())), catalog.epoch(),
                "PUBLIC".equals(source.visibility()) && "DOCUMENT_REVIEWED".equals(source.reviewStatus()));
    }

    private Draft draftById(String id) {
        long documentId;
        try {
            documentId = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new WikiProductException(404, "知识草稿不存在");
        }
        List<Draft> rows = jdbc.query("""
                SELECT d.id,d.source_key,d.title,v.normalized_body,v.version_label,d.source_url,
                       d.keywords_json,d.review_status,d.updated_by,d.updated_at,d.origin_ref
                FROM knowledge_document d
                JOIN knowledge_document_version v ON v.document_id=d.id AND v.state='DRAFT'
                WHERE d.id=? AND d.document_kind='PLATFORM_HELP' AND d.deleted=FALSE
                """, (rs, index) -> draft(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                time(rs.getTimestamp(10)), nickname(rs.getInt(9)), rs.getString(11)), documentId);
        if (rows.size() != 1) throw new WikiProductException(404, "知识草稿不存在");
        return rows.getFirst();
    }

    private static Draft draft(String id, String sourceId, String title, String text, String version, String sourceUrl,
                               String keywords, String state, String updatedAt, String operator, String sourceRef) {
        List<String> parsed = keywords == null || keywords.isBlank()
                ? List.of() : JSON.parseArray(keywords, String.class);
        return new Draft(id, sourceId, title, text, version, sourceUrl,
                sourceUrlLabel(sourceUrl), parsed == null ? List.of() : parsed,
                state, updatedAt, operator, sourceRef);
    }

    private static String sourceUrlLabel(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) return "全站通用（未关联页面）";
        return SOURCE_URL_LABELS.getOrDefault(sourceUrl, "平台页面");
    }

    private record DraftInput(String title, String text, String version, String sourceUrl, String sourceRefKind,
                              List<String> keywords) { }

    /**
     * 草稿未选具体页面时，发布用 G3 已有的“全站通用”锚点 {@link KnowledgeCatalog#GENERAL_SOURCE_URL}。
     * 仍然走 {@link KnowledgeCatalog#validSourceUrl(String)} 白名单校验，非法页面不会被接受。
     */
    static String publishedSourceUrl(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).strip();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return KnowledgeCatalog.GENERAL_SOURCE_URL;
        }
        return value;
    }

    private static DraftInput validateDraft(String title, String text, String version, String sourceUrl, List<String> keywords) {
        String safeTitle = title == null ? "" : title.strip();
        String safeText = text == null ? "" : text.strip();
        String safeVersion = version == null ? "" : version.strip();
        if (safeTitle.isEmpty() || safeTitle.length() > 80 || safeTitle.codePoints().anyMatch(Character::isISOControl)) throw bad();
        if (safeText.isEmpty() || safeText.length() > 20000) throw bad();
        if (!safeVersion.matches("[a-zA-Z0-9._-]{1,40}")) throw bad();
        String safeSourceUrl = sourceUrl == null ? "" : sourceUrl.strip();
        String sourceRefKind;
        if (safeSourceUrl.isEmpty()) {
            safeSourceUrl = null;
            sourceRefKind = "DOCUMENT";
        } else {
            if (!KnowledgeCatalog.validSourceUrl(safeSourceUrl)) {
                throw new WikiProductException(400, "请选择知识关联的页面");
            }
            sourceRefKind = "PLATFORM_PAGE";
        }
        List<String> clean = new ArrayList<>();
        if (keywords != null) {
            if (keywords.size() > 20) throw new WikiProductException(400, "检索关键词最多20个");
            for (String keyword : keywords) {
                String value = keyword == null ? "" : keyword.strip();
                if (value.isEmpty()) continue;
                if (value.length() > 30 || value.codePoints().anyMatch(Character::isISOControl)) {
                    throw new WikiProductException(400, "每个检索关键词最多30字");
                }
                if (!clean.contains(value)) clean.add(value);
            }
        }
        return new DraftInput(safeTitle, safeText, safeVersion, safeSourceUrl, sourceRefKind, List.copyOf(clean));
    }

    private void requireStorage() {
        if (jdbc == null) throw new WikiProductException(503, "知识存储暂不可用");
    }

    private static String time(Timestamp timestamp) {
        return timestamp == null ? "" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timestamp);
    }

    private String nickname(int actorId) {
        if (jdbc == null || actorId <= 0) return "管理员";
        try {
            String name = jdbc.queryForObject("SELECT COALESCE(NULLIF(nickname,''),username) FROM sys_user WHERE id=?",
                    String.class, actorId);
            return name == null || name.isBlank() ? "管理员" : name;
        } catch (RuntimeException unavailable) {
            return "管理员";
        }
    }

    /**
     * 版本历史由 {@code knowledge_document_version} 承载，正文本身就保存在版本行中，
     * 因此更新前无需另存一份归档；此处只做可见性校验。
     */
    private void beginArchive(KnowledgeDocument previous, int actor) {
        if (jdbc == null) {
            return;
        }
    }

    /** 目录更新成功后，把新正文落成新版本，并把旧版本标记为被替换。 */
    private void finishArchive(KnowledgeDocument previous, long generation, String state) {
        if (jdbc == null) {
            return;
        }
        try {
            KnowledgeDocument current = lifecycle.snapshot().source(previous.sourceId());
            if (current == null) {
                return;
            }
            Long documentId = jdbc.query("SELECT id FROM knowledge_document WHERE source_key=?",
                    rs -> rs.next() ? rs.getLong(1) : null, previous.sourceId());
            if (documentId == null) {
                return;
            }
            String sha = KnowledgeCatalog.hash(KnowledgeCatalog.normalize(current.text()));
            Integer existing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM knowledge_document_version
                    WHERE document_id=? AND version_label=?
                    """, Integer.class, documentId, current.version());
            if (existing != null && existing > 0) {
                jdbc.update("""
                        UPDATE knowledge_document_version
                        SET raw_body=?,normalized_body=?,normalized_sha256=?,char_length=?,state='CURRENT',
                            published_at=CURRENT_TIMESTAMP(6)
                        WHERE document_id=? AND version_label=?
                        """, current.text(), current.text(), sha, current.text().length(), documentId, current.version());
            } else {
                jdbc.update("""
                        UPDATE knowledge_document_version SET state='SUPERSEDED'
                        WHERE document_id=? AND state IN ('CURRENT','PENDING')
                        """, documentId);
                Integer next = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_document_version WHERE document_id=?
                        """, Integer.class, documentId);
                jdbc.update("""
                        INSERT INTO knowledge_document_version(document_id,version_no,version_label,raw_body,
                            normalized_body,raw_format,normalized_sha256,char_length,body_available,body_provenance,
                            state,published_at,created_by_kind)
                        VALUES(?,?,?,?,?,'TEXT',?,?,TRUE,'LEGACY_JSON','CURRENT',CURRENT_TIMESTAMP(6),'SYSTEM')
                        """, documentId, next, current.version(), current.text(), current.text(), sha,
                        current.text().length());
            }
            jdbc.update("""
                    UPDATE knowledge_document d
                    JOIN knowledge_document_version v ON v.document_id=d.id AND v.state='CURRENT'
                    SET d.current_published_version_id=v.id,d.current_version_no=v.version_no,
                        d.content_sha256=v.normalized_sha256,d.review_status='APPROVED',
                        d.publish_status='PUBLISHED',d.updated_by_kind='SYSTEM'
                    WHERE d.id=?
                    """, documentId);
        } catch (RuntimeException unavailable) {
            log.warn("知识历史版本同步失败，原因类型={}", unavailable.getClass().getSimpleName());
        }
    }

    /** 目录已回退或来源未变化时，不留下半成品版本。 */
    private void settleArchive(KnowledgeDocument previous, String archivedState) {
        if (jdbc == null) {
            return;
        }
        try {
            KnowledgeDocument current = lifecycle.snapshot().source(previous.sourceId());
            boolean applied = current != null && !current.version().equals(previous.version());
            if (applied) {
                finishArchive(previous, lifecycle.snapshot().epoch(), archivedState);
            }
        } catch (RuntimeException unavailable) {
            log.warn("知识历史版本状态无法确认，原因类型={}", unavailable.getClass().getSimpleName());
        }
    }
    private static KnowledgeDocument source(KnowledgeCatalog catalog, String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,80}")) throw bad();
        KnowledgeDocument source = catalog.source(id);
        if (source == null) throw new WikiProductException(404, "知识文档不存在");
        return source;
    }
    private void require(String permission) { if (!security.hasKnowledgePermission(permission)) throw denied(); }
    private static WikiProductException bad() { return new WikiProductException(400, "知识管理请求参数无效"); }
    private static WikiProductException denied() { return new WikiProductException(403, "没有此知识管理操作权限"); }
}
