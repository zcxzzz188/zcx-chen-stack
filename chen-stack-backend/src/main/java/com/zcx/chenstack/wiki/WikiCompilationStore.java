package com.zcx.chenstack.wiki;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** G3 专属持久预算；预留只增不退，UNKNOWN 不产生免费重试资格。写入顺序为来源→job→budget。 */
final class WikiCompilationStore {
    record Attempt(int number, String state) {}
    record AffectedTopic(String requestId, String topic, List<Long> snapshots) {}
    private final JdbcTemplate jdbc;

    WikiCompilationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void open(String requestId, String model, WikiCompilationService.Options options) {
        jdbc.update("""
                INSERT INTO wiki_compile_budget(request_id,model,max_calls,max_units,output_tokens,deadline_at)
                VALUES(?,?,?,?,?,TIMESTAMPADD(MICROSECOND,?,CURRENT_TIMESTAMP(6)))
                """, requestId, model, options.maxCalls(), options.maxUnits(), options.outputTokens(), options.timeout().toNanos() / 1000);
    }

    Attempt reserve(String requestId, String phase, int bytes) {
        var limits = jdbc.queryForMap("SELECT * FROM wiki_compile_budget WHERE request_id=? FOR UPDATE", requestId);
        if (expired(requestId)) throw new IllegalStateException("WIKI_COMPILE_TIMEOUT");
        int count = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt WHERE request_id=?", Integer.class, requestId));
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(reserved_units),0) FROM wiki_compile_attempt WHERE request_id=?", Long.class, requestId);
        long units = Math.addExact(bytes, ((Number) limits.get("output_tokens")).longValue());
        if (count >= ((Number) limits.get("max_calls")).intValue()
                || Math.addExact(Objects.requireNonNull(used), units) > ((Number) limits.get("max_units")).longValue()) {
            throw new IllegalStateException("WIKI_BUDGET_EXHAUSTED");
        }
        jdbc.update("""
                INSERT INTO wiki_compile_attempt(request_id,call_no,phase,input_bytes,reserved_units,state)
                VALUES(?,?,?,?,?,'PREPARED')
                """, requestId, count + 1, phase, bytes, units);
        return new Attempt(count + 1, "PREPARED");
    }

    void dispatch(String requestId, int number) {
        if (jdbc.update("UPDATE wiki_compile_attempt SET state='DISPATCHING' WHERE request_id=? AND call_no=? AND state='PREPARED'", requestId, number) != 1) {
            throw new IllegalStateException("WIKI_SEND_ALREADY_CLAIMED");
        }
    }

    void complete(String requestId, int number, String state, Long usage) {
        jdbc.update("""
                UPDATE wiki_compile_attempt SET state=?,usage_tokens=? WHERE request_id=? AND call_no=?
                AND state IN ('PREPARED','DISPATCHING')
                """, state, usage, requestId, number);
    }

    void stop(String requestId, String reason) {
        jdbc.update("UPDATE wiki_compile_budget SET first_stop=COALESCE(first_stop,?) WHERE request_id=?", reason, requestId);
    }

    boolean expired(String requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT deadline_at<=CURRENT_TIMESTAMP(6) FROM wiki_compile_budget WHERE request_id=?", Boolean.class, requestId));
    }

    void abandon(String requestId) {
        jdbc.update("UPDATE wiki_compile_attempt SET state='UNKNOWN' WHERE request_id=? AND state IN ('PREPARED','DISPATCHING')", requestId);
        stop(requestId, "RECOVERED_UNKNOWN");
    }

    void topics(String requestId, WikiDraftCompiler.Result result) {
        result.dependencies().forEach((topic, sources) -> sources.forEach(snapshot -> jdbc.update(
                "INSERT INTO wiki_compile_topic(request_id,topic_key,snapshot_id) VALUES(?,?,?)", requestId, topic, snapshot)));
    }

    Set<String> knownTopics() {
        return new TreeSet<>(jdbc.queryForList("SELECT DISTINCT topic_key FROM wiki_compile_topic ORDER BY topic_key LIMIT 128", String.class));
    }

    List<AffectedTopic> affected(WikiFoundation.SourceKey source) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT t.request_id,t.topic_key FROM wiki_compile_topic t
                JOIN wiki_source_snapshot s ON s.id=t.snapshot_id JOIN wiki_source o ON o.id=s.source_id
                WHERE o.source_type=? AND o.source_key=? ORDER BY t.request_id,t.topic_key
                """, source.type().name(), source.id());
        return rows.stream().map(row -> {
            String request = (String) row.get("request_id"), topic = (String) row.get("topic_key");
            return new AffectedTopic(request, topic, jdbc.queryForList(
                    "SELECT snapshot_id FROM wiki_compile_topic WHERE request_id=? AND topic_key=? ORDER BY snapshot_id", Long.class, request, topic));
        }).toList();
    }
}
