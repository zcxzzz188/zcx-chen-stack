package com.zcx.chenstack.wiki;

import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.utils.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.*;

import static com.zcx.chenstack.wiki.WikiViews.*;

/** Human review and publication authority, independent of model-writable P2 drafts. */
public final class WikiPublicationService {
    private record Row(Revision view, String approvedHash) {}
    private record Pointer(Long revisionId, Integer version, boolean withdrawn, String publishedAt) {}
    private record Visible(Card card, String markdown, List<Citation> citations) {}
    private final JdbcTemplate jdbc;
    private final WikiFoundation foundation;
    private final AdminSecurity security;
    private final TransactionTemplate transaction;

    public WikiPublicationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                  WikiFoundation foundation, AdminSecurity security) {
        this.jdbc = jdbc; this.foundation = foundation; this.security = security;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        Integer triggers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
                AND TRIGGER_NAME IN ('wiki_article_changed','wiki_article_deleted','wiki_help_changed','wiki_help_deleted')
                """, Integer.class);
        if (!Integer.valueOf(4).equals(triggers)) throw new IllegalStateException("WIKI_INVALIDATION_MIGRATION_REQUIRED");
    }

    public Revision create(String requestId, Long pageId, String title, String summary, String markdown) {
        int actor = operator("ai:wiki:edit");
        text(title, 160); text(summary, 500);
        if (pageId != null) positive(pageId);
        return foundation.withValidDraft(requestId, (draft, sources) -> {
            String body = publicMarkdown(markdown == null ? draft.markdown() : markdown);
            text(body, 900_000);
            if (body.getBytes(StandardCharsets.UTF_8).length > 900_000) throw bad();
            long target;
            if (pageId == null) {
                GeneratedKeyHolder keys = new GeneratedKeyHolder();
                jdbc.update(c -> c.prepareStatement("INSERT INTO wiki_page(withdrawn) VALUES(TRUE)", Statement.RETURN_GENERATED_KEYS), keys);
                target = Objects.requireNonNull(keys.getKey()).longValue();
            } else { target = pageId; pointer(target); }
            int version = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version),0)+1 FROM wiki_revision WHERE page_id=?", Integer.class, target));
            String manifest = manifest(requestId);
            String hash = bodyHash(title, summary, body);
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(c -> {
                var insert = c.prepareStatement("""
                        INSERT INTO wiki_revision(page_id,version,request_id,title,summary,markdown,body_hash,manifest_hash)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                insert.setLong(1, target); insert.setInt(2, version); insert.setString(3, requestId);
                insert.setString(4, title); insert.setString(5, summary); insert.setString(6, body);
                insert.setString(7, hash); insert.setString(8, manifest); return insert;
            }, keys);
            Row result = row(Objects.requireNonNull(keys.getKey()).longValue(), false);
            event(result.view(), "CREATE", actor);
            return result.view();
        });
    }

    public Revision submit(long id, String expectedHash) { return review(id, expectedHash, false); }
    public Revision approve(long id, String expectedHash, boolean evidence, boolean conflicts) {
        if (!evidence || !conflicts) throw new WikiProductException(400, "请先核查来源证据与冲突");
        return review(id, expectedHash, true);
    }

    private Revision review(long id, String expectedHash, boolean approve) {
        int actor = operator(approve ? "ai:wiki:review" : "ai:wiki:edit"); positive(id);
        Row initial = row(id, false);
        return foundation.withValidDraft(initial.view().requestId(), (draft, sources) -> {
            Row current = row(id, true); verify(current, false);
            Revision revision = current.view();
            if (!Objects.equals(revision.bodyHash(), expectedHash)
                    || !(approve ? "IN_REVIEW" : "DRAFT").equals(revision.state())) throw conflict();
            if (approve) {
                jdbc.update("UPDATE wiki_revision SET state='APPROVED',approved_hash=?,approved_by=? WHERE id=?",
                        revision.bodyHash(), actor, id);
            } else { jdbc.update("UPDATE wiki_revision SET state='IN_REVIEW' WHERE id=?", id); }
            event(revision, approve ? "APPROVE" : "SUBMIT", actor);
            return row(id, false).view();
        });
    }

    public Revision publish(long id, String expectedHash, Integer expectedCurrentVersion) {
        operator("ai:wiki:publish"); positive(id);
        if (!Objects.equals(row(id, false).view().bodyHash(), expectedHash)) throw conflict();
        return point(id, null, expectedCurrentVersion, false);
    }

    public Revision restore(long pageId, long revisionId, Integer expectedCurrentVersion) {
        operator("ai:wiki:publish"); positive(pageId); positive(revisionId);
        return point(revisionId, pageId, expectedCurrentVersion, true);
    }

    private Revision point(long id, Long expectedPageId, Integer expectedVersion, boolean restore) {
        int actor = operator("ai:wiki:publish");
        Row initial = row(id, false);
        return foundation.withValidDraft(initial.view().requestId(), (draft, sources) -> {
            Row current = row(id, true); verify(current, true);
            Revision revision = current.view();
            if (expectedPageId != null && expectedPageId != revision.pageId()) throw missing();
            if (!(restore ? "PUBLISHED" : "APPROVED").equals(revision.state())) throw conflict();
            Pointer pointer = pointer(revision.pageId());
            if (!Objects.equals(pointer.version(), expectedVersion)) throw conflict();
            jdbc.update("UPDATE wiki_revision SET state='PUBLISHED' WHERE id=?", id);
            jdbc.update("UPDATE wiki_page SET current_revision_id=?,withdrawn=FALSE,published_at=CURRENT_TIMESTAMP(6) WHERE id=?",
                    id, revision.pageId());
            event(revision, restore ? "RESTORE" : "PUBLISH", actor);
            return row(id, false).view();
        });
    }

    public void withdraw(long pageId, Integer expectedCurrentVersion) {
        int actor = operator("ai:wiki:publish"); positive(pageId);
        transaction.executeWithoutResult(status -> {
            Pointer pointer = pointer(pageId);
            if (!Objects.equals(pointer.version(), expectedCurrentVersion)) throw conflict();
            jdbc.update("UPDATE wiki_page SET withdrawn=TRUE WHERE id=?", pageId);
            jdbc.update("INSERT INTO wiki_review_event(page_id,revision_id,action,actor_id) VALUES(?,?,'WITHDRAW',?)",
                    pageId, pointer.revisionId(), actor);
        });
    }

    /** 管理员人工编写：仍使用已授权来源快照，不调用模型；随后进入同一审核发布链。 */
    public Revision createManual(String requestId, List<Long> snapshotIds, String title, String summary, String markdown) {
        operator("ai:wiki:edit");
        foundation.createManualDraft(requestId, snapshotIds, title, summary, markdown);
        return create(requestId, null, title, summary, markdown);
    }

    /** 基于当前页面已有来源起草新版本；不改变已发布正文，审核发布后才切换。 */
    public Revision editPage(long pageId, String title, String summary, String markdown) {
        operator("ai:wiki:edit");
        positive(pageId);
        List<String> requests = jdbc.queryForList(
                "SELECT request_id FROM wiki_revision WHERE page_id=? ORDER BY version DESC LIMIT 1", String.class, pageId);
        if (requests.isEmpty()) throw missing();
        try {
            return create(requests.getFirst(), pageId, title, summary, markdown);
        } catch (SecurityException invalidated) {
            throw new WikiProductException(409, "页面来源已变化，请先重新核对来源并整理，再更新正文");
        } catch (IllegalStateException stale) {
            throw new WikiProductException(409, "页面草稿状态已变化，请刷新后重试");
        }
    }

    public List<ManagedPage> managedPages() { return managedPages(""); }

    public List<ManagedPage> managedPages(String query) {
        operator();
        String q = query == null ? "" : query.strip();
        if (q.length() > 80) throw bad();
        List<ManagedPage> pages = jdbc.query("""
                SELECT p.id,
                       COALESCE(r.title,(SELECT x.title FROM wiki_revision x WHERE x.page_id=p.id ORDER BY x.version DESC LIMIT 1)),
                        r.version,p.withdrawn,DATE_FORMAT(p.published_at,'%Y-%m-%d %H:%i:%s'),
                       COALESCE(r.summary,(SELECT x.summary FROM wiki_revision x WHERE x.page_id=p.id ORDER BY x.version DESC LIMIT 1)),
                       DATE_FORMAT((SELECT MAX(x.created_at) FROM wiki_revision x WHERE x.page_id=p.id),'%Y-%m-%d %H:%i:%s')
                FROM wiki_page p LEFT JOIN wiki_revision r ON r.id=p.current_revision_id
                WHERE (?='' OR LOCATE(?,COALESCE(r.title,(SELECT x.title FROM wiki_revision x WHERE x.page_id=p.id ORDER BY x.version DESC LIMIT 1)))>0
                       OR LOCATE(?,COALESCE(r.summary,(SELECT x.summary FROM wiki_revision x WHERE x.page_id=p.id ORDER BY x.version DESC LIMIT 1)))>0
                       OR EXISTS (SELECT 1 FROM wiki_revision x WHERE x.page_id=p.id AND LOCATE(?,x.markdown)>0))
                ORDER BY (SELECT MAX(x.created_at) FROM wiki_revision x WHERE x.page_id=p.id) DESC,p.id DESC LIMIT 200
                """, (rs, n) -> new ManagedPage(rs.getLong(1), rs.getString(2), rs.getObject(3, Integer.class),
                rs.getBoolean(4), false, rs.getString(6), rs.getString(7), rs.getString(5), "UNAVAILABLE"),
                q, q, q, q);
        return pages.stream().map(page -> {
            String reason = statusReason(page.id(), page.withdrawn(), page.currentVersion());
            return new ManagedPage(page.id(), page.title(), page.currentVersion(), page.withdrawn(),
                    "PUBLISHED".equals(reason), page.summary(), page.updatedAt(), page.publishedAt(), reason);
        }).toList();
    }

    /** 管理端完整正文阅读；即使页面当前不可公开也返回已入库正文和真实原因。 */
    public ManagedPageDetail pageDetail(long pageId) {
        operator();
        positive(pageId);
        List<PageRow> rows = jdbc.query("""
                SELECT p.withdrawn,DATE_FORMAT(p.published_at,'%Y-%m-%d %H:%i:%s'),p.current_revision_id FROM wiki_page p WHERE p.id=?
                """, (rs, n) -> new PageRow(rs.getBoolean(1), rs.getString(2), rs.getObject(3, Long.class)), pageId);
        if (rows.size() != 1) throw missing();
        PageRow page = rows.getFirst();
        List<Revision> history = versions(pageId);
        if (history.isEmpty()) throw missing();
        Revision visible = history.stream().filter(revision -> Objects.equals(revision.id(), page.currentRevisionId()))
                .findFirst().orElse(history.getFirst());
        String reason = statusReason(pageId, page.withdrawn(), page.currentRevisionId() == null ? null : visible.version());
        return new ManagedPageDetail(pageId, visible.title(), visible.version(), visible.summary(), visible.markdown(),
                page.publishedAt(), topics(visible.requestId()), citationsFor(visible.requestId()),
                "PUBLISHED".equals(reason), reason, reasonDetail(reason), history);
    }

    private record PageRow(boolean withdrawn, String publishedAt, Long currentRevisionId) { }

    private String statusReason(long pageId, boolean withdrawn, Integer currentVersion) {
        if (withdrawn) return "WITHDRAWN";
        if (currentVersion == null) return "UNPUBLISHED";
        List<Long> ids = jdbc.queryForList("""
                SELECT current_revision_id FROM wiki_page WHERE id=? AND withdrawn=FALSE AND current_revision_id IS NOT NULL
                """, Long.class, pageId);
        if (ids.size() != 1) return "UNAVAILABLE";
        try {
            Row initial = row(ids.getFirst(), false);
            foundation.withValidDraft(initial.view().requestId(), (draft, sources) -> {
                Row current = row(initial.view().id(), true);
                verify(current, true);
                Pointer pointer = pointer(pageId);
                if (pointer.withdrawn() || !Objects.equals(pointer.revisionId(), current.view().id())
                        || !"PUBLISHED".equals(current.view().state())) throw missing();
                return null;
            });
            return "PUBLISHED";
        } catch (SecurityException invalidated) {
            return "SOURCE_CHANGED";
        } catch (IllegalStateException stale) {
            return "DRAFT_INVALID";
        } catch (WikiProductException unavailable) {
            return "UNAVAILABLE";
        }
    }

    private static String reasonDetail(String reason) {
        return switch (reason) {
            case "PUBLISHED" -> "当前版本已发布，小辰可以使用。";
            case "WITHDRAWN" -> "页面已撤回，读者和小辰都不会使用。";
            case "UNPUBLISHED" -> "还没有已发布的版本；审核并发布后才会对外可用。";
            case "SOURCE_CHANGED" -> "引用的来源已更新或被撤销，需要重新核对来源并生成新版本。";
            case "DRAFT_INVALID" -> "生成此页面的草稿或来源状态已变化，需要重新整理；已发布正文仍在目录中。";
            default -> "页面当前不可用，请查看版本历史。";
        };
    }

    private List<Citation> citationsFor(String requestId) {
        return jdbc.query("""
                SELECT s.title,s.origin_version,
                       CASE WHEN o.source_type='ARTICLE' THEN
                           (SELECT CONCAT('/user/',a.user_id,'/article/',a.id) FROM article a WHERE a.id=o.source_key)
                           ELSE NULL END
                FROM wiki_job_source js
                JOIN wiki_source_snapshot s ON s.id=js.snapshot_id
                JOIN wiki_source o ON o.id=s.source_id
                WHERE js.request_id=? ORDER BY js.snapshot_id LIMIT 32
                """, (rs, n) -> new Citation(rs.getString(1), rs.getString(3), rs.getString(2)), requestId);
    }

    public List<Revision> versions(long pageId) {
        operator(); positive(pageId);
        return jdbc.query(SELECT + " WHERE r.page_id=? ORDER BY r.version DESC LIMIT 200", ROW, pageId)
                .stream().map(Row::view).toList();
    }

    public Page<Card> list(String query, String topic, int page, int size) {
        pagination(page, size); boundedQuery(query); if (topic != null && topic.length() > 100) throw bad();
        List<Card> visible = visible(query).stream().map(Visible::card).toList();
        List<String> topics = visible.stream().flatMap(c -> c.topics().stream()).distinct().sorted().toList();
        List<Card> filtered = visible.stream().filter(c -> topic == null || topic.isBlank() || c.topics().contains(topic)).toList();
        return new Page<>(filtered.stream().skip((long) (page - 1) * size).limit(size).toList(), filtered.size(), page, size, topics);
    }

    public PublishedPage read(long pageId) {
        positive(pageId);
        Visible result = validPage(pageId);
        List<Card> related = visible("").stream().map(Visible::card)
                .filter(c -> c.id() != pageId && !Collections.disjoint(c.topics(), result.card().topics())).limit(6).toList();
        Card card = result.card();
        // Resolve cross references only against the currently visible catalog.
        String markdown = result.markdown();
        for (Card link : related) {
            for (String key : link.topics()) markdown = markdown.replace("[[topic:" + key + "]]", "[" + key + "](/wiki/" + link.id() + ")");
        }
        return new PublishedPage(card.id(), card.title(), markdown, card.version(), card.publishedAt(), card.topics(), result.citations(), related);
    }

    private List<Visible> visible(String query) {
        // shortcut: synchronous bounded keyword scan, add a measured index strategy above 500 published pages.
        List<Long> ids = jdbc.queryForList("""
                SELECT p.id FROM wiki_page p JOIN wiki_revision r ON r.id=p.current_revision_id
                WHERE p.withdrawn=FALSE AND (?='' OR LOCATE(?,CONCAT(r.title,CHAR(10),r.summary,CHAR(10),r.markdown))>0)
                ORDER BY p.id DESC LIMIT 501
                """, Long.class, query == null ? "" : query.strip(), query == null ? "" : query.strip());
        if (ids.size() > 500) throw new WikiProductException(503, "Wiki目录超过首版检索上限");
        List<Visible> result = new ArrayList<>();
        for (long id : ids) {
            try { result.add(validPage(id)); }
            catch (WikiProductException unavailable) { if (unavailable.status() != 404) throw unavailable; }
        }
        return List.copyOf(result);
    }

    private Visible validPage(long pageId) {
        List<Long> ids = jdbc.queryForList("SELECT current_revision_id FROM wiki_page WHERE id=? AND withdrawn=FALSE AND current_revision_id IS NOT NULL",
                Long.class, pageId);
        if (ids.size() != 1) throw missing();
        Row initial = row(ids.getFirst(), false);
        try {
            return foundation.withValidDraft(initial.view().requestId(), (draft, sources) -> {
                Row current = row(initial.view().id(), true); verify(current, true);
                Pointer pointer = pointer(pageId);
                Revision revision = current.view();
                if (pointer.withdrawn() || !Objects.equals(pointer.revisionId(), revision.id())
                        || !"PUBLISHED".equals(revision.state())) throw missing();
                List<String> topics = topics(revision.requestId());
                List<Citation> citations = sources.stream().map(this::citation).toList();
                return new Visible(new Card(pageId, revision.title(), revision.summary(), topics, revision.version(), pointer.publishedAt()),
                        revision.markdown(), citations);
            });
        } catch (SecurityException | IllegalStateException invalid) { throw missing(); }
        catch (WikiProductException invalid) { if (invalid.status() == 409) throw missing(); throw invalid; }
    }

    private Citation citation(WikiFoundation.Snapshot source) {
        WikiFoundation.Candidate material = source.material();
        String url = null;
        if (material.key().type() == WikiFoundation.SourceType.ARTICLE) {
            Integer user = jdbc.queryForObject("SELECT user_id FROM article WHERE id=?", Integer.class, material.key().id());
            if (user != null && user > 0) url = "/user/" + user + "/article/" + material.key().id();
        }
        return new Citation(material.title(), url, material.originVersion());
    }

    private void verify(Row row, boolean approved) {
        Revision revision = row.view();
        if (!bodyHash(revision.title(), revision.summary(), revision.markdown()).equals(revision.bodyHash())
                || !manifest(revision.requestId()).equals(revision.manifestHash())
                || approved && !Objects.equals(row.approvedHash(), revision.bodyHash())) throw conflict();
    }

    private String manifest(String requestId) {
        return jdbc.queryForObject("SELECT manifest_hash FROM wiki_compile_job WHERE request_id=?", String.class, requestId);
    }

    List<String> topics(String requestId) {
        return jdbc.queryForList("SELECT DISTINCT topic_key FROM wiki_compile_topic WHERE request_id=? ORDER BY topic_key", String.class, requestId);
    }

    private Pointer pointer(long pageId) {
        List<Pointer> rows = jdbc.query("""
                SELECT p.current_revision_id,r.version,p.withdrawn,p.published_at FROM wiki_page p
                LEFT JOIN wiki_revision r ON r.id=p.current_revision_id WHERE p.id=? FOR UPDATE
                """, (rs, n) -> new Pointer(rs.getObject(1, Long.class), rs.getObject(2, Integer.class), rs.getBoolean(3), rs.getString(4)), pageId);
        if (rows.size() != 1) throw missing(); return rows.getFirst();
    }

    private Row row(long id, boolean lock) {
        List<Row> rows = jdbc.query(SELECT + " WHERE r.id=?" + (lock ? " FOR UPDATE" : ""), ROW, id);
        if (rows.size() != 1) throw missing(); return rows.getFirst();
    }

    private static final String SELECT = """
            SELECT r.*,p.current_revision_id FROM wiki_revision r JOIN wiki_page p ON p.id=r.page_id
            """;
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(new Revision(rs.getLong("id"), rs.getLong("page_id"),
            rs.getInt("version"), rs.getString("request_id"), rs.getString("title"), rs.getString("summary"), rs.getString("markdown"),
            rs.getString("state"), rs.getString("body_hash"), rs.getString("manifest_hash"), rs.getString("created_at"),
            Objects.equals(rs.getObject("current_revision_id", Long.class), rs.getLong("id"))), rs.getString("approved_hash"));

    private void event(Revision revision, String action, int actor) {
        jdbc.update("INSERT INTO wiki_review_event(page_id,revision_id,action,actor_id,body_hash) VALUES(?,?,?,?,?)",
                revision.pageId(), revision.id(), action, actor, revision.bodyHash());
    }

    int operator() { return operator("ai:wiki:read"); }

    int operator(String permission) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Integer actor = SecurityUtils.getUserId();
        if (authentication == null || !authentication.isAuthenticated() || actor == null || actor <= 0 || !(security.isAdmin() || security.hasKnowledgePermission(permission))) {
            throw new WikiProductException(403, "需要管理员权限");
        }
        return actor;
    }

    static String publicMarkdown(String markdown) {
        if (markdown == null) throw bad();
        return markdown.lines().filter(line -> !line.startsWith("# Wiki 编译草稿") && !line.startsWith("状态：DRAFT")
                        && !line.startsWith("任务：") && !line.startsWith("来源清单版本：") && !line.startsWith("编译版本："))
                .map(line -> line.stripLeading().startsWith("- snapshot=") ? quoteOnly(line) : line)
                .reduce(new StringBuilder(), (out, line) -> out.append(line).append('\n'), StringBuilder::append).toString().strip();
    }

    private static String quoteOnly(String line) {
        int index = line.indexOf(" quote=“");
        return index < 0 ? "" : "  > " + line.substring(index + 7);
    }

    private static String bodyHash(String title, String summary, String markdown) {
        return KnowledgeCatalog.hash(title + "\u0000" + summary + "\u0000" + markdown);
    }
    static void pagination(int page, int size) { if (page < 1 || page > 10000 || size < 1 || size > 50) throw bad(); }
    static void boundedQuery(String query) { if (query != null && query.length() > 80) throw bad(); }
    static void positive(long id) { if (id <= 0) throw bad(); }
    private static void text(String value, int max) { if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) throw bad(); }
    static WikiProductException bad() { return new WikiProductException(400, "Wiki请求参数无效"); }
    static WikiProductException missing() { return new WikiProductException(404, "Wiki页面不可用"); }
    static WikiProductException conflict() { return new WikiProductException(409, "内容、来源或当前版本已变化，请重新核查"); }
}
