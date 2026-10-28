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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 内部后端 API；未注册 HTTP/模型工具。授权动作使用现有 admin 身份，编译结果只能存为草稿。
 * 每个调用拥有短事务；不可把返回快照缓存后绕过 inputs/readDraft 的当前来源校验。
 */
public final class WikiFoundation {
    public enum SourceType { ARTICLE, PLATFORM_HELP }

    public record SourceKey(SourceType type, String id) {
        public SourceKey {
            Objects.requireNonNull(type);
            if (id == null || !id.matches("[a-z0-9_-]{1,80}")) throw new IllegalArgumentException("WIKI_SOURCE_KEY_INVALID");
            if (type == SourceType.ARTICLE) {
                try { if (Integer.parseInt(id) <= 0 || !id.equals(Integer.toString(Integer.parseInt(id)))) throw new NumberFormatException(); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("WIKI_ARTICLE_ID_INVALID"); }
            }
        }
    }

    public record Candidate(SourceKey key, String title, String rawJson, String text,
                            String fingerprint, String originVersion, String parserVersion) {}
    public record Snapshot(long id, long sourceId, long generation, Candidate material) {}
    public record Job(String requestId, String manifestHash, String contractHash, String state) {}
    public record CompilationInput(Job job, List<Snapshot> sources) {
        public CompilationInput { sources = List.copyOf(sources); }
    }
    public record Draft(String requestId, String markdown, String bodyHash) {}
    private record Source(long id, SourceKey key, long generation, String hash, boolean active) {}
    private record Checked<T>(boolean valid, T value) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WikiSourceReader reader;
    private final AdminSecurity security;

    public WikiFoundation(JdbcTemplate jdbc, PlatformTransactionManager manager, WikiSourceReader reader, AdminSecurity security) {
        this.jdbc = jdbc;
        this.reader = reader;
        this.security = security;
        transaction = new TransactionTemplate(manager);
        // 不继承调用者的 REPEATABLE_READ 历史视图，也不将来源锁持有到调用者的长事务结束。
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public Candidate inspect(SourceKey key) {
        operator();
        Objects.requireNonNull(key);
        return reader.withAuthority(List.of(key), () -> transaction.execute(status -> reader.read(key)));
    }

    /** 操作者必须先查看 inspect 的当前材料，授权精确 hash；公开可读本身不是编译授权。 */
    public Snapshot authorize(SourceKey key, String reviewedFingerprint) {
        int actor = operator();
        hash(reviewedFingerprint);
        Objects.requireNonNull(key);
        return reader.withAuthority(List.of(key), () -> transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO wiki_source(source_type,source_key,generation,allowed_hash,active,authorized_by)
                    VALUES(?,?,0,?,FALSE,?) ON DUPLICATE KEY UPDATE id=id
                    """, key.type().name(), key.id(), reviewedFingerprint, actor);
            Source source = source(key);
            Candidate current = reader.read(key);
            if (!reviewedFingerprint.equals(current.fingerprint())) throw new SecurityException("WIKI_REVIEW_HASH_STALE");
            long generation = source.generation();
            if (!source.active() || !source.hash().equals(reviewedFingerprint)) {
                generation = Math.incrementExact(generation);
                jdbc.update("UPDATE wiki_source SET generation=?,allowed_hash=?,active=TRUE,authorized_by=? WHERE id=?",
                        generation, reviewedFingerprint, actor, source.id());
            }
            List<Snapshot> existing = snapshots("WHERE s.source_id=? AND s.generation=?", source.id(), generation);
            if (!existing.isEmpty()) {
                if (!existing.getFirst().material().equals(current)) throw new SecurityException("WIKI_SNAPSHOT_INTEGRITY");
                return existing.getFirst();
            }
            var keys = new GeneratedKeyHolder();
            final long revision = generation;
            jdbc.update(connection -> {
                var insert = connection.prepareStatement("""
                        INSERT INTO wiki_source_snapshot(source_id,generation,fingerprint,title,raw_json,parsed_text,origin_version,parser_version)
                        VALUES(?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                insert.setLong(1, source.id()); insert.setLong(2, revision);
                insert.setString(3, current.fingerprint()); insert.setString(4, current.title());
                insert.setString(5, current.rawJson()); insert.setString(6, current.text());
                insert.setString(7, current.originVersion()); insert.setString(8, current.parserVersion());
                return insert;
            }, keys);
            return new Snapshot(Objects.requireNonNull(keys.getKey()).longValue(), source.id(), generation, current);
        }));
    }

    public void revoke(SourceKey key) {
        operator();
        transaction.executeWithoutResult(status -> disable(source(key)));
    }

    /** P1只持久化编译意图，不调度或调用 Provider；同 requestId 不允许换输入或编译合同。 */
    public Job prepare(String requestId, List<Long> snapshotIds, String contractHash) {
        operator();
        requestId(requestId); hash(contractHash);
        List<Long> ids = ids(snapshotIds);
        return checked(() -> load(ids), sources -> {
            String manifest = KnowledgeCatalog.hash(contractHash + ":" +
                    sources.stream().map(s -> s.id() + ":" + s.material().fingerprint()).toList());
            jdbc.update("""
                    INSERT INTO wiki_compile_job(request_id,manifest_hash,contract_hash,state) VALUES(?,?,?,'QUEUED')
                    ON DUPLICATE KEY UPDATE request_id=request_id
                    """, requestId, manifest, contractHash);
            Job job = job(requestId);
            if (!manifest.equals(job.manifestHash()) || !contractHash.equals(job.contractHash())) {
                throw new IllegalStateException("WIKI_REQUEST_ID_CONFLICT");
            }
            for (Snapshot snapshot : sources) {
                jdbc.update("INSERT INTO wiki_job_source(request_id,snapshot_id) VALUES(?,?) ON DUPLICATE KEY UPDATE snapshot_id=snapshot_id",
                        requestId, snapshot.id());
            }
            return job;
        });
    }

    /** 仅内部编译调用；每次发送输入前重新调用，不能信任第三方导出的来源声明。 */
    public CompilationInput inputs(String requestId) {
        return withCurrentInputs(requestId, Function.identity());
    }

    /** 短回调内持有来源行锁及帮助权威；只做内存交付，禁止网络、模型调用及嵌套来源写入。 */
    public <T> T withCurrentInputs(String requestId, Function<CompilationInput, T> action) {
        requestId(requestId);
        Objects.requireNonNull(action);
        return checked(() -> load(jobSources(requestId)), sources -> {
            Job job = job(requestId);
            if (!Set.of("QUEUED", "RUNNING").contains(job.state())) throw new IllegalStateException("WIKI_JOB_NOT_QUEUED");
            return action.apply(new CompilationInput(job, sources));
        });
    }

    /** 认领及预算初始化共享短事务；回调只允许本地 SQL。 */
    CompilationInput start(String requestId, java.util.function.Consumer<CompilationInput> initialize) {
        operator(); requestId(requestId);
        return checked(() -> load(jobSources(requestId)), sources -> {
            Job current = job(requestId);
            if (!"QUEUED".equals(current.state())) throw new IllegalStateException("WIKI_JOB_ALREADY_CLAIMED");
            CompilationInput input = new CompilationInput(current, sources);
            initialize.accept(input);
            jdbc.update("UPDATE wiki_compile_job SET state='RUNNING' WHERE request_id=?", requestId);
            return input;
        });
    }

    public Job status(String requestId) {
        operator(); requestId(requestId);
        return transaction.execute(status -> job(requestId));
    }

    void stop(String requestId, String state) {
        operator(); requestId(requestId);
        if (!Set.of("FAILED", "INVALIDATED").contains(state)) throw new IllegalArgumentException("WIKI_STOP_STATE_INVALID");
        transaction.executeWithoutResult(status -> jdbc.update(
                "UPDATE wiki_compile_job SET state=? WHERE request_id=? AND state='RUNNING'", state, requestId));
    }

    /** 恢复只收敛状态，不读取/释放已失效来源。 */
    void recover(String requestId, Runnable expiredAttempt) {
        operator(); requestId(requestId);
        transaction.executeWithoutResult(status -> {
            String current = job(requestId).state();
            if (!Set.of("RUNNING", "CANCELLED").contains(current)) return;
            expiredAttempt.run();
            if ("RUNNING".equals(current)) jdbc.update("UPDATE wiki_compile_job SET state='FAILED' WHERE request_id=?", requestId);
        });
    }

    /** 无 status/publish 参数；外部编译器不能赋予正式发布权。 */
    public Draft saveDraft(String requestId, String markdown) {
        return saveDraft(requestId, markdown, () -> {});
    }

    /** 编译器元数据与草稿在同一来源重验事务中提交。 */
    Draft saveDraft(String requestId, String markdown, Runnable metadata) {
        requestId(requestId);
        if (markdown == null || markdown.isBlank() || markdown.getBytes(StandardCharsets.UTF_8).length > 1_000_000) {
            throw new IllegalArgumentException("WIKI_DRAFT_SIZE_INVALID");
        }
        return checked(() -> load(jobSources(requestId)), sources -> {
            Job job = job(requestId);
            String bodyHash = KnowledgeCatalog.hash(markdown);
            List<Draft> prior = drafts(requestId);
            if (!prior.isEmpty()) {
                if (!"DRAFT_READY".equals(job.state()) || !bodyHash.equals(prior.getFirst().bodyHash())
                        || !markdown.equals(prior.getFirst().markdown())) throw new IllegalStateException("WIKI_DRAFT_CONFLICT");
                return prior.getFirst();
            }
            if (!Set.of("QUEUED", "RUNNING").contains(job.state())) throw new IllegalStateException("WIKI_JOB_NOT_QUEUED");
            metadata.run();
            jdbc.update("INSERT INTO wiki_draft(request_id,markdown,body_hash,state) VALUES(?,?,?,'DRAFT')", requestId, markdown, bodyHash);
            if (jdbc.update("UPDATE wiki_compile_job SET state='DRAFT_READY' WHERE request_id=? AND state IN ('QUEUED','RUNNING')", requestId) != 1) {
                throw new IllegalStateException("WIKI_JOB_CONFLICT");
            }
            return new Draft(requestId, markdown, bodyHash);
        });
    }

    public Draft readDraft(String requestId) {
        operator(); requestId(requestId);
        return withValidDraft(requestId, (draft, sources) -> draft);
    }

    /** 可信发布服务的短事务入口；调用者负责 HTTP 权限和公开 DTO 投影。 */
    <T> T withValidDraft(String requestId, java.util.function.BiFunction<Draft, List<Snapshot>, T> action) {
        requestId(requestId);
        return checked(() -> load(jobSources(requestId)), sources -> {
            if (!"DRAFT_READY".equals(job(requestId).state())) throw new IllegalStateException("WIKI_DRAFT_NOT_READY");
            List<Draft> rows = drafts(requestId);
            if (rows.size() != 1 || !KnowledgeCatalog.hash(rows.getFirst().markdown()).equals(rows.getFirst().bodyHash())) {
                throw new SecurityException("WIKI_DRAFT_INTEGRITY");
            }
            return action.apply(rows.getFirst(), sources);
        });
    }

    public void cancel(String requestId) {
        cancel(requestId, () -> {});
    }

    void cancel(String requestId, Runnable metadata) {
        operator(); requestId(requestId);
        transaction.executeWithoutResult(status -> {
            job(requestId);
            if (jdbc.update("UPDATE wiki_compile_job SET state='CANCELLED' WHERE request_id=? AND state IN ('QUEUED','RUNNING')", requestId) == 1) {
                metadata.run();
            }
        });
    }

    public List<String> affectedJobs(SourceKey key) {
        operator();
        return transaction.execute(status -> jdbc.queryForList("""
                SELECT DISTINCT d.request_id FROM wiki_job_source d
                JOIN wiki_source_snapshot s ON s.id=d.snapshot_id
                JOIN wiki_source o ON o.id=s.source_id
                WHERE o.source_type=? AND o.source_key=? ORDER BY d.request_id
                """, String.class, key.type().name(), key.id()));
    }

    private <T> T checked(Supplier<List<Snapshot>> selection, Function<List<Snapshot>, T> action) {
        // 快照与依赖只增不改；无行锁的选择完成后，先取得帮助权威，再开始当前读事务。
        List<Snapshot> sources = Objects.requireNonNull(transaction.execute(status -> selection.get())).stream()
                .sorted(Comparator.comparingLong(Snapshot::sourceId)).toList();
        if (sources.isEmpty() || sources.size() > 32
                || sources.stream().map(Snapshot::sourceId).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("WIKI_SOURCE_SET_INVALID");
        }
        Checked<T> result = reader.withAuthority(sources.stream().map(s -> s.material().key()).toList(), () -> transaction.execute(status -> {
            boolean valid = true;
            for (Snapshot snapshot : sources) {
                Source source = source(snapshot.material().key());
                if (!source.active() || source.id() != snapshot.sourceId() || source.generation() != snapshot.generation()
                        || !source.hash().equals(snapshot.material().fingerprint())) { valid = false; continue; }
                Candidate current;
                try { current = reader.read(source.key()); }
                catch (SecurityException rejected) { disable(source); valid = false; continue; }
                if (!current.fingerprint().equals(source.hash())) { disable(source); valid = false; continue; }
                if (!current.equals(snapshot.material())
                        || !KnowledgeCatalog.hash(snapshot.material().rawJson()).equals(source.hash())) {
                    throw new SecurityException("WIKI_SNAPSHOT_INTEGRITY");
                }
            }
            // 失效记录先提交再抛错；不能因异常回滚而让旧授权在资料恢复原样后复活。
            return valid ? new Checked<>(true, action.apply(sources)) : new Checked<T>(false, null);
        }));
        if (!Objects.requireNonNull(result).valid()) throw new SecurityException("WIKI_SOURCE_INVALIDATED");
        return result.value();
    }

    private void disable(Source source) {
        if (source.active()) jdbc.update("UPDATE wiki_source SET active=FALSE,generation=? WHERE id=?",
                Math.incrementExact(source.generation()), source.id());
    }

    private Source source(SourceKey key) {
        List<Source> rows = jdbc.query("""
                SELECT id,generation,allowed_hash,active FROM wiki_source WHERE source_type=? AND source_key=? FOR UPDATE
                """, (rs, n) -> new Source(rs.getLong(1), key, rs.getLong(2), rs.getString(3), rs.getBoolean(4)),
                key.type().name(), key.id());
        if (rows.size() != 1) throw new SecurityException("WIKI_SOURCE_NOT_AUTHORIZED");
        return rows.getFirst();
    }

    private List<Snapshot> load(List<Long> ids) {
        List<Snapshot> result = new ArrayList<>();
        for (long id : ids) {
            List<Snapshot> rows = snapshots("WHERE s.id=?", id);
            if (rows.size() != 1) throw new SecurityException("WIKI_SNAPSHOT_NOT_FOUND");
            result.add(rows.getFirst());
        }
        return List.copyOf(result);
    }

    private List<Snapshot> snapshots(String where, Object... arguments) {
        return jdbc.query("""
                SELECT s.id,s.source_id,s.generation,o.source_type,o.source_key,s.title,s.raw_json,s.parsed_text,
                       s.fingerprint,s.origin_version,s.parser_version
                FROM wiki_source_snapshot s JOIN wiki_source o ON o.id=s.source_id
                """ + where, SNAPSHOT, arguments);
    }

    private static final RowMapper<Snapshot> SNAPSHOT = (rs, n) -> new Snapshot(rs.getLong(1), rs.getLong(2), rs.getLong(3),
            new Candidate(new SourceKey(SourceType.valueOf(rs.getString(4)), rs.getString(5)),
                    rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)));

    private Job job(String id) {
        List<Job> rows = jdbc.query("SELECT request_id,manifest_hash,contract_hash,state FROM wiki_compile_job WHERE request_id=? FOR UPDATE",
                (rs, n) -> new Job(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        if (rows.size() != 1) throw new IllegalStateException("WIKI_JOB_NOT_FOUND");
        return rows.getFirst();
    }

    private List<Long> jobSources(String id) {
        List<Long> rows = jdbc.queryForList("SELECT snapshot_id FROM wiki_job_source WHERE request_id=? ORDER BY snapshot_id", Long.class, id);
        if (rows.isEmpty()) throw new IllegalStateException("WIKI_JOB_NOT_FOUND");
        return ids(rows);
    }

    private List<Draft> drafts(String id) {
        return jdbc.query("SELECT request_id,markdown,body_hash FROM wiki_draft WHERE request_id=? AND state='DRAFT'",
                (rs, n) -> new Draft(rs.getString(1), rs.getString(2), rs.getString(3)), id);
    }

    private int operator() {
        Integer actor = SecurityUtils.getUserId();
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || actor == null || actor <= 0 || !security.isAdmin()) {
            throw new SecurityException("WIKI_OPERATOR_REQUIRED");
        }
        return actor;
    }

    private static List<Long> ids(List<Long> values) {
        if (values == null || values.isEmpty() || values.size() > 32 || values.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("WIKI_SOURCE_SET_INVALID");
        }
        return values.stream().distinct().sorted().toList();
    }

    private static void requestId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,80}")) throw new IllegalArgumentException("WIKI_REQUEST_ID_INVALID");
    }

    private static void hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("WIKI_HASH_INVALID");
    }
}

