package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.wiki.WikiViews;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
public class KnowledgePages {
    static final String CURRENT_VERSION = """
            (SELECT COALESCE(MAX(r.version),0)+1 FROM wiki_page_revision r WHERE r.wiki_page_id=p.id)
            """;
    static final String PUBLISHED = """
            p.status='PUBLISHED' AND NOT p.review_required
            AND EXISTS (SELECT 1 FROM wiki_page_source s WHERE s.wiki_page_id=p.id)
            AND NOT EXISTS (SELECT 1 FROM wiki_page_source s JOIN rag_knowledge k ON k.id=s.knowledge_id
                            WHERE s.wiki_page_id=p.id AND NOT k.enabled)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeWikiGenerator generator;

    public KnowledgePages(JdbcTemplate jdbc, PlatformTransactionManager manager, KnowledgeWikiGenerator generator) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.generator = generator;
    }

    public record Page(long id, String title, String summary, String content, String status,
                       LocalDateTime updatedAt, LocalDateTime publishedAt) {}
    public record Source(long id, String title, String content, LocalDateTime updatedAt) {}
    public record PageSource(long id, String title, String module, boolean enabled) {}
    public record Revision(int version, String title, String summary, String content, LocalDateTime createdAt) {}

    public List<Revision> revisions(long id) {
        read(id);
        return jdbc.query("""
                SELECT version,title,summary,NULL,created_at FROM wiki_page_revision
                WHERE wiki_page_id=? ORDER BY version DESC
                """, KnowledgePages::revision, id);
    }

    public Revision revision(long id, int version) {
        return jdbc.query("""
                SELECT version,title,summary,content,created_at FROM wiki_page_revision
                WHERE wiki_page_id=? AND version=?
                """, KnowledgePages::revision, id, version).stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    private static Revision revision(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Revision(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, LocalDateTime.class));
    }

    public List<PageSource> sources(long pageId) {
        return jdbc.query("""
                SELECT k.id,k.title,d.title,k.enabled FROM wiki_page_source s
                JOIN rag_knowledge k ON k.id=s.knowledge_id
                LEFT JOIN knowledge_document d ON d.id=k.document_id
                WHERE s.wiki_page_id=? ORDER BY k.id
                """, (rs, row) -> new PageSource(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), pageId);
    }

    static final RowMapper<Page> PAGE = (rs, row) -> new Page(rs.getLong(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class));

    public List<Page> list(int offset) {
        return jdbc.query("""
                SELECT id,title,summary,NULL,status,updated_at,published_at FROM wiki_page
                ORDER BY updated_at DESC,id DESC LIMIT 51 OFFSET ?
                """, PAGE, offset);
    }

    public record ListItem(long id, String title, String summary, String status, LocalDateTime updatedAt,
                           boolean reviewRequired, long version) {}

    public KnowledgeList<ListItem> list(KnowledgeList.Filter filter, String state, Boolean reviewRequired) {
        String from = """
                 FROM (SELECT p.id,p.title,p.summary,p.status,p.updated_at,p.review_required,
                              1+COALESCE((SELECT MAX(r.version) FROM wiki_page_revision r WHERE r.wiki_page_id=p.id),0) AS version
                       FROM wiki_page p
                       WHERE LOCATE(?,p.title)>0 AND (? IS NULL OR p.status=?)
                         AND (? IS NULL OR p.review_required=?)
                         AND (? IS NULL OR p.updated_at>=?) AND (? IS NULL OR p.updated_at<=?)) p
                """;
        return KnowledgeList.query(jdbc, filter, from, "p.updated_at DESC,p.id DESC",
                (rs, row) -> new ListItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class), rs.getBoolean(6), rs.getLong(7)),
                filter.keyword(), state, state, reviewRequired, reviewRequired,
                filter.from(), filter.from(), filter.to(), filter.to());
    }

    public Page read(long id) {
        return jdbc.query("SELECT id,title,summary,content,status,updated_at,published_at FROM wiki_page WHERE id=?",
                PAGE, id).stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    public void edit(long id, String title, String summary, String content) {
        transaction.executeWithoutResult(status -> {
            saveDraft(lockPage(id), title.strip(), summary.strip(), content.strip());
        });
    }

    public void restore(long id, int version, LocalDateTime expectedUpdatedAt) {
        transaction.executeWithoutResult(status -> {
            Page current = lockPage(id);
            if (!current.updatedAt().equals(expectedUpdatedAt)) {
                throw KnowledgeDocException.conflict("KNOWLEDGE_WIKI_STALE", "页面已变更，请刷新后重试");
            }
            Revision previous = revision(id, version);
            saveDraft(current, previous.title(), previous.summary(), previous.content());
        });
    }

    private Page lockPage(long id) {
        return jdbc.query("""
                SELECT id,title,summary,content,status,updated_at,published_at FROM wiki_page WHERE id=? FOR UPDATE
                """, PAGE, id).stream().findFirst().orElseThrow(KnowledgeDocException::missing);
    }

    static void snapshot(JdbcTemplate jdbc, long id) {
        jdbc.update("""
                INSERT INTO wiki_page_revision(wiki_page_id,version,title,summary,content)
                SELECT p.id, COALESCE((SELECT MAX(r.version) FROM wiki_page_revision r WHERE r.wiki_page_id=p.id),0)+1,
                       p.title,p.summary,p.content FROM wiki_page p WHERE p.id=?
                """, id);
    }

    private void saveDraft(Page current, String title, String summary, String content) {
        snapshot(jdbc, current.id());
        jdbc.update("""
                UPDATE wiki_page SET title=?,summary=?,content=?,status='DRAFT',manually_edited=TRUE,
                  review_required=FALSE,updated_at=NOW(6) WHERE id=?
                """, title, summary, content, current.id());
    }

    public long generate(List<Long> knowledgeIds) {
        List<Long> ids = knowledgeIds.stream().distinct().sorted().toList();
        List<Source> sources = sourceEntries(ids, false);
        var draft = generator.generate(sources.stream()
                .map(source -> new KnowledgeWikiGenerator.Source(source.title(), source.content())).toList());
        return transaction.execute(status -> {
            if (!sources.equals(sourceEntries(ids, true))) {
                throw KnowledgeDocException.conflict("KNOWLEDGE_WIKI_STALE", "来源已更新，请重新生成");
            }
            jdbc.update("INSERT INTO wiki_page(title,summary,content,manually_edited) VALUES (?,?,?,TRUE)",
                    draft.title(), draft.summary(), draft.content());
            long pageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.batchUpdate("INSERT INTO wiki_page_source(wiki_page_id,knowledge_id) VALUES (?,?)",
                    ids.stream().map(id -> new Object[]{pageId, id}).toList());
            return pageId;
        });
    }

    private List<Source> sourceEntries(List<Long> ids, boolean lock) {
        if (ids.isEmpty() || ids.size() > 5) {
            throw KnowledgeDocException.bad("KNOWLEDGE_WIKI_SOURCE", "请选择一至五条已启用知识");
        }
        List<Source> result = ids.stream().map(id -> jdbc.query("""
                SELECT id,title,content,updated_at FROM rag_knowledge WHERE id=? AND enabled
                  AND index_status IN ('READY','UNINDEXED')
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Source(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getObject(4, LocalDateTime.class)), id).stream().findFirst()
                .orElseThrow(() -> KnowledgeDocException.conflict("KNOWLEDGE_WIKI_SOURCE", "所选来源已停用或正在更新"))).toList();
        return result;
    }

    public void publish(long id, boolean published) {
        publish(id, published, null);
    }

    public void publish(long id, boolean published, LocalDateTime expectedUpdatedAt) {
        String sql = published ? """
                UPDATE wiki_page p SET p.status='PUBLISHED',p.published_at=NOW(6) WHERE p.id=? AND NOT p.review_required
                  AND EXISTS (SELECT 1 FROM wiki_page_source s WHERE s.wiki_page_id=p.id)
                  AND NOT EXISTS (SELECT 1 FROM wiki_page_source s JOIN rag_knowledge k ON k.id=s.knowledge_id
                                  WHERE s.wiki_page_id=p.id AND (NOT k.enabled OR k.index_status IN ('DELETING','PENDING')))
                """ : "UPDATE wiki_page p SET p.status='OFFLINE' WHERE p.id=?";
        int changed = jdbc.update(sql + " AND (? IS NULL OR p.updated_at=?)",
                id, expectedUpdatedAt, expectedUpdatedAt);
        if (changed == 0) {
            throw KnowledgeDocException.conflict("KNOWLEDGE_WIKI_SOURCE",
                    expectedUpdatedAt == null ? "页面或有效来源不可用" : "页面已变更、待审核或有效来源不可用，请刷新核对");
        }
    }

    public void delete(long id) {
        if (jdbc.update("DELETE FROM wiki_page WHERE id=?", id) == 0) {
            throw KnowledgeDocException.missing();
        }
    }

    public WikiViews.Page<WikiViews.Card> publicList(String query, int page, int size) {
        if (page < 1 || page > 10000 || size < 1 || size > 50 || query.length() > 160) {
            throw KnowledgeDocException.bad("KNOWLEDGE_PAGE_QUERY", "Wiki 查询参数无效");
        }
        String where = " FROM wiki_page p WHERE " + PUBLISHED + " AND (?='' OR LOCATE(?,CONCAT(p.title,p.summary,p.content))>0)";
        List<WikiViews.Card> items = jdbc.query("SELECT p.id,p.title,p.summary,p.published_at," + CURRENT_VERSION + where
                + " ORDER BY p.published_at DESC,p.id DESC LIMIT ? OFFSET ?", (rs, row) ->
                new WikiViews.Card(rs.getLong(1), rs.getString(2), rs.getString(3), List.of(), rs.getInt(5),
                        rs.getObject(4, LocalDateTime.class).toString()), query, query, size, (page - 1) * size);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, query, query);
        return new WikiViews.Page<>(items, total, page, size, List.of());
    }

    public WikiViews.PublishedPage publicPage(long id) {
        WikiViews.PublishedPage page = jdbc.query("SELECT p.id,p.title,p.content,p.published_at," + CURRENT_VERSION
                        + " FROM wiki_page p WHERE p.id=? AND " + PUBLISHED,
                (rs, row) -> new WikiViews.PublishedPage(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getInt(5), rs.getObject(4, LocalDateTime.class).toString(), List.of(), List.of(), List.of()), id)
                .stream().findFirst().orElseThrow(KnowledgeDocException::missing);
        List<WikiViews.Citation> citations = jdbc.query("""
                SELECT k.title,k.source_url,k.revision FROM rag_knowledge k JOIN wiki_page_source s ON k.id=s.knowledge_id
                WHERE s.wiki_page_id=? AND k.enabled ORDER BY k.id
                """, (rs, row) -> {
            String url = rs.getString(2);
            return new WikiViews.Citation(rs.getString(1),
                    url != null && url.matches("/user/[1-9][0-9]*/article/[1-9][0-9]*") ? url : null, rs.getString(3));
        }, id);
        return new WikiViews.PublishedPage(id, page.title(), page.markdown(), page.version(), page.publishedAt(),
                List.of(), citations, List.of());
    }
}
