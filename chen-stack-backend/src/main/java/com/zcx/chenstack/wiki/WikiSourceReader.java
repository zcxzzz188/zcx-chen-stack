package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.enums.EditStatusEnum;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.enums.VisibleRangeEnum;
import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Collection;
import java.util.function.Supplier;

import static com.zcx.chenstack.wiki.WikiFoundation.*;

/** 当前读必须在 WikiFoundation 的短事务内执行；不调用原有文章服务的作者例外。 */
public final class WikiSourceReader {
    static final String PARSER = "jsoup-1.18.3-wiki-v1";
    private static final int MAX_BYTES = 1_000_000;
    private final JdbcTemplate jdbc;
    private final CatalogLifecycle catalog;
    private final ObjectMapper json;

    public WikiSourceReader(JdbcTemplate jdbc, CatalogLifecycle catalog, ObjectMapper json) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.json = json;
    }

    Candidate read(SourceKey key) {
        return key.type() == SourceType.ARTICLE ? article(key) : help(key);
    }

    /** 与 G2 保持 Lifecycle→DB 锁顺序；帮助来源的当前权威覆盖整个短事务。 */
    <T> T withAuthority(Collection<SourceKey> keys, Supplier<T> action) {
        if (keys.stream().noneMatch(key -> key.type() == SourceType.PLATFORM_HELP)) return action.get();
        synchronized (catalog) { return action.get(); }
    }

    private Candidate article(SourceKey key) {
        List<Candidate> rows = jdbc.query("""
                SELECT user_id,title,description,content,tag,reprint_type,reprint_url,
                       edit_status,examine_status,visible_range,is_deleted
                FROM article WHERE id=? FOR SHARE
                """, (rs, n) -> {
            if (!Integer.valueOf(0).equals(rs.getObject("is_deleted", Integer.class))
                    || !Integer.valueOf(EditStatusEnum.PUBLISHED.getCode()).equals(rs.getObject("edit_status", Integer.class))
                    || !Integer.valueOf(ExamineStatusEnum.PASS.getCode()).equals(rs.getObject("examine_status", Integer.class))
                    || !Integer.valueOf(VisibleRangeEnum.ALL.getCode()).equals(rs.getObject("visible_range", Integer.class))) {
                throw denied();
            }
            String raw = rs.getString("content");
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw denied();
            var document = Jsoup.parse(raw);
            document.select("script,style,noscript,iframe,object,embed").remove();
            String text = document.body().wholeText().strip();
            String payload = encode(Arrays.asList(PARSER, key.type().name(), key.id(),
                    rs.getInt("user_id"), rs.getString("title"), rs.getString("description"),
                    raw, rs.getString("tag"), rs.getObject("reprint_type"), rs.getString("reprint_url"), text));
            String hash = KnowledgeCatalog.hash(payload);
            return candidate(key, rs.getString("title"), payload, text, hash);
        }, Integer.parseInt(key.id()));
        if (rows.size() != 1) throw denied();
        return rows.getFirst();
    }

    private Candidate help(SourceKey key) {
        // 调用者必须先持有 Lifecycle monitor，再取得任何来源行锁。
        if (!Thread.holdsLock(catalog)) throw new IllegalStateException("WIKI_HELP_AUTHORITY_LOCK_REQUIRED");
        KnowledgeDocument canonical;
        try { canonical = catalog.snapshot().source(key.id()); }
        catch (CatalogLifecycle.Invalidated unavailable) { throw denied(); }
        if (canonical == null || !"PUBLIC".equals(canonical.visibility())
                || !"DOCUMENT_REVIEWED".equals(canonical.reviewStatus())) throw denied();
        List<KnowledgeDocument> rows = jdbc.query("""
                SELECT document_json,tombstone,deleted,version FROM rag_source_state
                WHERE source_id=? FOR SHARE
                """, (rs, n) -> {
            if (rs.getBoolean("tombstone") || rs.getBoolean("deleted")) throw denied();
            try {
                KnowledgeDocument persisted = json.readValue(rs.getString("document_json"), KnowledgeDocument.class);
                if (!persisted.equals(canonical) || !persisted.version().equals(rs.getString("version"))) throw denied();
                return persisted;
            } catch (JsonProcessingException malformed) { throw denied(); }
        }, key.id());
        if (rows.size() != 1) throw denied();
        String payload = encode(List.of("help-text-v1", rows.getFirst()));
        return candidate(key, canonical.title(), payload, canonical.text(), canonical.version());
    }

    private Candidate candidate(SourceKey key, String title, String rawJson, String text, String version) {
        if (title == null || title.isBlank() || title.length() > 255 || text == null || text.isBlank()
                || rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw denied();
        return new Candidate(key, title, rawJson, text, KnowledgeCatalog.hash(rawJson), version,
                key.type() == SourceType.ARTICLE ? PARSER : "help-text-v1");
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw denied(); }
    }

    private static SecurityException denied() { return new SecurityException("WIKI_SOURCE_NOT_ADMITTED"); }
}

