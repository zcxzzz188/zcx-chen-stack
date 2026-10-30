package com.zcx.chenstack.wiki;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static com.zcx.chenstack.wiki.WikiViews.*;

/** Admin-only product adapter. Model identity, tools and budgets are never request parameters. */
public final class WikiManagementService {
    private final JdbcTemplate jdbc;
    private final WikiFoundation foundation;
    private final WikiPublicationService publication;
    private final ObjectProvider<WikiCompilationService> compiler;
    private final Semaphore workers = new Semaphore(2);
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public WikiManagementService(JdbcTemplate jdbc, WikiFoundation foundation, WikiPublicationService publication,
                                 ObjectProvider<WikiCompilationService> compiler) {
        this.jdbc = jdbc; this.foundation = foundation; this.publication = publication; this.compiler = compiler;
    }

    public Page<Source> sources(String query, int page, int size) {
        publication.operator(); WikiPublicationService.pagination(page, size); WikiPublicationService.boundedQuery(query);
        String q = query == null ? "" : query.strip();
        List<SourceKey> keys = new ArrayList<>(jdbc.query("""
                SELECT id FROM article WHERE edit_status=? AND examine_status=? AND visible_range=? AND is_deleted=0
                AND (?='' OR LOCATE(?,title)>0) ORDER BY id DESC LIMIT 201
                """, (rs, n) -> new SourceKey(SourceType.ARTICLE, rs.getString(1)),
                com.zcx.chenstack.domain.enums.EditStatusEnum.PUBLISHED.getCode(),
                com.zcx.chenstack.domain.enums.ExamineStatusEnum.PASS.getCode(),
                com.zcx.chenstack.domain.enums.VisibleRangeEnum.ALL.getCode(), q, q));
        keys.addAll(jdbc.query("SELECT source_id FROM rag_source_state WHERE tombstone=FALSE AND deleted=FALSE ORDER BY source_id LIMIT 201",
                (rs, n) -> new SourceKey(SourceType.PLATFORM_HELP, rs.getString(1))));
        if (keys.size() > 200) throw new WikiProductException(503, "请缩小来源查询范围（首版最多200项）");
        List<Source> admitted = new ArrayList<>();
        for (SourceKey key : keys) {
            try {
                Source view = inspect(key);
                if (q.isEmpty() || view.title().contains(q)) admitted.add(view);
            } catch (SecurityException unavailable) { /* Current P1 admission wins over SQL preselection. */ }
        }
        return new Page<>(admitted.stream().skip((long) (page - 1) * size).limit(size).toList(), admitted.size(), page, size, List.of());
    }

    public Source inspect(SourceKey key) {
        publication.operator(); Candidate current = foundation.inspect(key);
        List<Source> rows = jdbc.query("""
                SELECT s.id,o.generation,o.active,o.allowed_hash FROM wiki_source o
                LEFT JOIN wiki_source_snapshot s ON s.source_id=o.id AND s.generation=o.generation
                WHERE o.source_type=? AND o.source_key=?
                """, (rs, n) -> source(current, rs.getObject(1, Long.class), rs.getObject(2, Long.class),
                rs.getBoolean(3) && current.fingerprint().equals(rs.getString(4))), key.type().name(), key.id());
        return rows.isEmpty() ? source(current, null, null, false) : rows.getFirst();
    }

    public Source authorize(SourceKey key, String fingerprint) {
        publication.operator(); Snapshot snapshot = foundation.authorize(key, fingerprint);
        return source(snapshot.material(), snapshot.id(), snapshot.generation(), true);
    }

    public void revoke(SourceKey key) { publication.operator(); foundation.revoke(key); }

    public Page<WikiViews.Job> jobs(int page, int size) {
        publication.operator(); WikiPublicationService.pagination(page, size);
        List<WikiViews.Job> rows = jdbc.query(JOB_SELECT + " ORDER BY j.created_at DESC,j.request_id DESC LIMIT ? OFFSET ?", (rs, n) -> job(rs), size, (page - 1) * size);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_job", Long.class);
        return new Page<>(rows, count == null ? 0 : count, page, size, List.of());
    }

    public WikiViews.Job createJob(String requestId, List<Long> snapshots, Set<String> topics) {
        publication.operator(); WikiCompilationService service = requiredCompiler();
        if (!workers.tryAcquire()) throw new WikiProductException(429, "编译任务已满，请稍后重试");
        boolean handedOff = false;
        try {
            WikiCompilationService.Options defaults = WikiCompilationService.Options.defaults();
            WikiCompilationService.Options options = new WikiCompilationService.Options(defaults.maxCalls(), defaults.maxUnits(),
                    defaults.outputTokens(), defaults.timeout(), topics == null ? Set.of() : topics);
            WikiFoundation.Job queued = service.queue(requestId, snapshots, options);
            if (!"QUEUED".equals(queued.state()) || !active.add(requestId)) return job(requestId);
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            Thread.ofVirtual().name("wiki-admin-compile").start(() -> {
                var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
                try { service.compile(requestId, snapshots, options); }
                catch (RuntimeException failure) {
                    // P2 handles failures after claim; a source can also fail the queued worker's first check.
                    jdbc.update("UPDATE wiki_compile_job SET state=? WHERE request_id=? AND state='QUEUED'",
                            failure instanceof SecurityException ? "INVALIDATED" : "FAILED", requestId);
                }
                finally { SecurityContextHolder.clearContext(); active.remove(requestId); workers.release(); }
            });
            handedOff = true;
            return job(requestId);
        } finally { if (!handedOff) workers.release(); }
    }

    public void cancel(String requestId) {
        publication.operator();
        foundation.cancel(requestId, () -> new WikiCompilationStore(jdbc).stop(requestId, "CANCELLED"));
    }

    public List<WikiCompilationService.RecompileTarget> affected(SourceKey key) {
        publication.operator();
        foundation.affectedJobs(key);
        return new WikiCompilationStore(jdbc).affected(key).stream()
                .map(topic -> new WikiCompilationService.RecompileTarget(topic.requestId(), topic.topic(), topic.snapshots())).toList();
    }

    public WikiViews.Draft draft(String requestId) {
        publication.operator();
        return foundation.withValidDraft(requestId, (draft, snapshots) -> new WikiViews.Draft(requestId, draft.markdown(), draft.bodyHash(),
                jdbc.queryForObject("SELECT manifest_hash FROM wiki_compile_job WHERE request_id=?", String.class, requestId), WikiDraftCompiler.VERSION,
                jdbc.queryForObject("SELECT created_at FROM wiki_draft WHERE request_id=?", String.class, requestId),
                snapshots.stream().map(s -> source(s.material(), s.id(), s.generation(), true)).toList(), publication.topics(requestId),
                draft.markdown().lines().filter(line -> line.contains("CONFLICT") || line.contains("UNSUPPORTED")).limit(128).toList()));
    }

    private WikiViews.Job job(String id) {
        List<WikiViews.Job> rows = jdbc.query(JOB_SELECT + " WHERE j.request_id=?", (rs, n) -> job(rs), id);
        if (rows.size() != 1) throw WikiPublicationService.missing(); return rows.getFirst();
    }

    private WikiViews.Job job(java.sql.ResultSet rs) throws java.sql.SQLException {
        String request = rs.getString("request_id");
        return new WikiViews.Job(request, rs.getString("state"), rs.getString("created_at"), rs.getString("model"),
                rs.getObject("max_calls", Integer.class), rs.getLong("used_calls"), rs.getObject("max_units", Long.class),
                rs.getLong("used_units"), rs.getString("first_stop"), publication.topics(request));
    }

    private static final String JOB_SELECT = """
            SELECT j.request_id,j.state,j.created_at,b.model,b.max_calls,b.max_units,b.first_stop,
            (SELECT COUNT(*) FROM wiki_compile_attempt a WHERE a.request_id=j.request_id) used_calls,
            (SELECT COALESCE(SUM(a.reserved_units),0) FROM wiki_compile_attempt a WHERE a.request_id=j.request_id) used_units
            FROM wiki_compile_job j LEFT JOIN wiki_compile_budget b ON b.request_id=j.request_id
            """;
    private WikiCompilationService requiredCompiler() {
        WikiCompilationService service = compiler.getIfAvailable();
        if (service == null) throw new WikiProductException(503, "Wiki编译器未启用，请联系管理员检查配置");
        return service;
    }
    private static Source source(Candidate c, Long snapshot, Long generation, boolean active) {
        return new Source(c.key().type().name(), c.key().id(), c.title(), c.text(), c.fingerprint(), c.originVersion(), c.parserVersion(), snapshot, generation, active);
    }
}
