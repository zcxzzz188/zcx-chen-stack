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
@lombok.extern.slf4j.Slf4j
public class AiKnowledgeManagementService {
    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Document(String sourceId, String title, String text, String version, String sourceType,
                           String sourceUrl, String visibility, String reviewStatus, Instant updatedAt,
                           List<String> keywords, String contentHash, long generation, boolean effective) {}
    public record Draft(String draftId, String sourceId, String title, String text, String version, String sourceUrl,
                        String sourceUrlLabel, List<String> keywords, String state, String updatedAt, String operator) {}
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
                SELECT id,source_id,title,text,version,source_url,keywords_json,state,updated_by,updated_at
                FROM rag_knowledge_draft WHERE state='DRAFT' AND (?='' OR LOCATE(?,title)>0)
                ORDER BY updated_at DESC,id DESC LIMIT 100
                """, (rs, index) -> draft(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                time(rs.getTimestamp(10)), nickname(rs.getInt(9))), q, q);
    }

    public Draft createDraft(String title, String text, String version, String sourceUrl, List<String> keywords) {
        require("ai:rag:manage");
        requireStorage();
        DraftInput input = validateDraft(title, text, version, sourceUrl, keywords);
        String id = "draft-" + UUID.randomUUID();
        String sourceId = "help-managed-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        int actor = SecurityUtils.getUserId();
        jdbc.update("""
                INSERT INTO rag_knowledge_draft(id,source_id,title,text,version,source_url,keywords_json,state,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,'DRAFT',?,?)
                """, id, sourceId, input.title, input.text, input.version, input.sourceUrl,
                JSON.toJSONString(input.keywords), actor, actor);
        return draftById(id);
    }

    public Draft updateDraft(String id, String title, String text, String version, String sourceUrl, List<String> keywords) {
        require("ai:rag:manage");
        requireStorage();
        DraftInput input = validateDraft(title, text, version, sourceUrl, keywords);
        int updated = jdbc.update("""
                UPDATE rag_knowledge_draft SET title=?,text=?,version=?,source_url=?,keywords_json=?,updated_by=?
                WHERE id=? AND state='DRAFT'
                """, input.title, input.text, input.version, input.sourceUrl, JSON.toJSONString(input.keywords),
                SecurityUtils.getUserId(), id);
        if (updated != 1) throw new WikiProductException(404, "知识草稿不存在或已发布");
        return draftById(id);
    }

    public void discardDraft(String id) {
        require("ai:rag:manage");
        requireStorage();
        String sourceId = jdbc.query("SELECT source_id FROM rag_knowledge_draft WHERE id=? AND state='DRAFT'",
                rs -> rs.next() ? rs.getString(1) : null, id);
        if (sourceId == null) throw new WikiProductException(404, "知识草稿不存在或已发布");
        if (lifecycle.snapshot().source(sourceId) != null) {
            throw new WikiProductException(409, "该知识已发布，请刷新列表，不能按草稿删除");
        }
        jdbc.update("DELETE FROM rag_knowledge_draft WHERE id=? AND state='DRAFT'", id);
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
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT source_id,title,text,version,source_url,keywords_json,state,publish_request_id,published_generation
                FROM rag_knowledge_draft WHERE id=?
                """, id);
        if (rows.isEmpty()) throw new WikiProductException(404, "知识草稿不存在");
        Map<String, Object> row = rows.getFirst();
        String sourceId = String.valueOf(row.get("source_id"));
        if ("PUBLISHED".equals(row.get("state"))) {
            Object previousRequest = row.get("publish_request_id");
            if (requestId.equals(previousRequest) && row.get("published_generation") != null) {
                return new RagKnowledgeAdministration.Result("manage-" + requestId, "COMPLETED",
                        ((Number) row.get("published_generation")).longValue());
            }
            throw new WikiProductException(409, "该知识已经发布，请刷新列表");
        }
        // 客户端未收到已成功操作的响应时，按同一幂等键补记完成，不重复写目录或索引。
        RagKnowledgeAdministration.Result completed = administration.completedCreate(requestId, sourceId);
        if (completed != null) {
            markDraftPublished(id, requestId, completed.generation());
            return completed;
        }
        if (lifecycle.snapshot().source(sourceId) != null) {
            throw new WikiProductException(409, "该知识已经发布，请刷新列表");
        }
        List<String> keywords = JSON.parseArray(String.valueOf(row.get("keywords_json")), String.class);
        KnowledgeDocument addition = new KnowledgeDocument(sourceId, String.valueOf(row.get("title")),
                String.valueOf(row.get("text")), String.valueOf(row.get("version")), "PLATFORM_HELP",
                String.valueOf(row.get("source_url")), "PUBLIC", "DOCUMENT_REVIEWED", Instant.now(), keywords);
        RagKnowledgeAdministration.Result result = administration.create(addition, expectedGeneration, requestId,
                SecurityUtils.getUserId());
        if ("COMPLETED".equals(result.status())) {
            markDraftPublished(id, requestId, result.generation());
        }
        return result;
    }

    private void markDraftPublished(String draftId, String requestId, long generation) {
        jdbc.update("""
                UPDATE rag_knowledge_draft SET state='PUBLISHED',published_generation=?,published_at=CURRENT_TIMESTAMP(6),
                    publish_request_id=?,updated_by=? WHERE id=? AND state='DRAFT'
                """, generation, requestId, SecurityUtils.getUserId(), draftId);
    }

    /** 仅返回被替换/撤销的历史正文；当前正文以知识目录为准。 */
    public List<Version> versions(String sourceId) {
        require("ai:rag:read");
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,80}")) throw bad();
        source(lifecycle.snapshot(), sourceId);
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT version,title,text,source_url,keywords_json,content_sha256,state,created_by,archived_at
                FROM rag_knowledge_version WHERE source_id=? AND state<>'PENDING'
                ORDER BY archived_at DESC,id DESC LIMIT 50
                """, (rs, index) -> new Version(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                SOURCE_URL_LABELS.getOrDefault(rs.getString(4), "平台页面"),
                JSON.parseArray(rs.getString(5), String.class), rs.getString(6), rs.getString(7),
                time(rs.getTimestamp(9)), nickname(rs.getInt(8))), sourceId);
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
        List<Draft> rows = jdbc.query("""
                SELECT id,source_id,title,text,version,source_url,keywords_json,state,updated_by,updated_at
                FROM rag_knowledge_draft WHERE id=?
                """, (rs, index) -> draft(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                time(rs.getTimestamp(10)), nickname(rs.getInt(9))), id);
        if (rows.size() != 1) throw new WikiProductException(404, "知识草稿不存在");
        return rows.getFirst();
    }

    private static Draft draft(String id, String sourceId, String title, String text, String version, String sourceUrl,
                               String keywords, String state, String updatedAt, String operator) {
        return new Draft(id, sourceId, title, text, version, sourceUrl,
                SOURCE_URL_LABELS.getOrDefault(sourceUrl, "平台页面"),
                JSON.parseArray(keywords, String.class), state, updatedAt, operator);
    }

    private record DraftInput(String title, String text, String version, String sourceUrl, List<String> keywords) { }

    private static DraftInput validateDraft(String title, String text, String version, String sourceUrl, List<String> keywords) {
        String safeTitle = title == null ? "" : title.strip();
        String safeText = text == null ? "" : text.strip();
        String safeVersion = version == null ? "" : version.strip();
        if (safeTitle.isEmpty() || safeTitle.length() > 80 || safeTitle.codePoints().anyMatch(Character::isISOControl)) throw bad();
        if (safeText.isEmpty() || safeText.length() > 20000) throw bad();
        if (!safeVersion.matches("[a-zA-Z0-9._-]{1,40}")) throw bad();
        if (!KnowledgeCatalog.validSourceUrl(sourceUrl)) throw new WikiProductException(400, "请选择知识关联的页面");
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
        return new DraftInput(safeTitle, safeText, safeVersion, sourceUrl, List.copyOf(clean));
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

    /** 在正式更新前保留旧正文；失败时按目录实际状态决定保留还是清理。 */
    private void beginArchive(KnowledgeDocument previous, int actor) {
        if (jdbc == null) return;
        try {
            jdbc.update("""
                    INSERT INTO rag_knowledge_version(source_id,version,title,text,source_url,keywords_json,
                        content_sha256,state,created_by) VALUES(?,?,?,?,?,?,?,'PENDING',?)
                    ON DUPLICATE KEY UPDATE state='PENDING',content_sha256=VALUES(content_sha256)
                    """, previous.sourceId(), previous.version(), previous.title(), previous.text(), previous.sourceUrl(),
                    JSON.toJSONString(previous.keywords() == null ? List.of() : previous.keywords()),
                    KnowledgeCatalog.hash(KnowledgeCatalog.normalize(previous.text())), actor);
        } catch (RuntimeException unavailable) {
            log.warn("知识历史版本暂存失败，原因类型={}", unavailable.getClass().getSimpleName());
        }
    }

    private void finishArchive(KnowledgeDocument previous, long generation, String state) {
        if (jdbc == null) return;
        try {
            jdbc.update("UPDATE rag_knowledge_version SET state=?,generation=? WHERE source_id=? AND version=?",
                    state, generation, previous.sourceId(), previous.version());
        } catch (RuntimeException unavailable) {
            log.warn("知识历史版本归档失败，原因类型={}", unavailable.getClass().getSimpleName());
        }
    }

    private void settleArchive(KnowledgeDocument previous, String archivedState) {
        if (jdbc == null) return;
        try {
            KnowledgeDocument current = lifecycle.snapshot().source(previous.sourceId());
            boolean applied = current != null && (!current.version().equals(previous.version())
                    || "REVOKED".equals(current.reviewStatus()));
            if (applied) finishArchive(previous, lifecycle.snapshot().epoch(), archivedState);
            else jdbc.update("DELETE FROM rag_knowledge_version WHERE source_id=? AND version=? AND state='PENDING'",
                    previous.sourceId(), previous.version());
        } catch (RuntimeException unavailable) {
            log.warn("知识历史版本状态无法确认，保留待核查记录，原因类型={}", unavailable.getClass().getSimpleName());
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
