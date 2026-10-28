package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FrameworkJsonCompatibilityTest {
    // Legacy wire expectations; execution evidence is reported separately from these assertions.
    static final String ARTICLE_JSON = "[\"jsoup-1.18.3-wiki-v1\",\"ARTICLE\",\"1\",7,\"兼容性资料\",\"旧格式\",\"<p>知识正文 😀</p>\",\"Java\",0,null,\"知识正文 😀\"]";
    static final String ARTICLE_HASH = "4b2caf308f4d51308e801cc6f63ff6969f90dadc27b4afb7f6b98489f0bd985b";
    static final String HELP_JSON = "[\"help-text-v1\",{\"sourceId\":\"help-old\",\"title\":\"旧帮助\",\"text\":\"旧正文\",\"version\":\"v1\",\"sourceType\":\"PLATFORM_HELP\",\"sourceUrl\":\"/editor\",\"visibility\":\"PUBLIC\",\"reviewStatus\":\"DOCUMENT_REVIEWED\",\"updatedAt\":\"1970-01-01T00:00:00Z\",\"keywords\":[\"Java\"]}]";

    @Test void existingArticleAndHelpCanonicalBytesAndDateFormatRemainStable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
                .withPropertyValues("spring.jackson2.date-format=yyyy-MM-dd HH:mm:ss", "spring.jackson2.time-zone=GMT+8")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    ObjectMapper json = context.getBean(ObjectMapper.class);
                    JdbcTemplate jdbc = mock(JdbcTemplate.class);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("is_deleted", Integer.class)).thenReturn(0);
                    when(rs.getObject("edit_status", Integer.class)).thenReturn(0);
                    when(rs.getObject("examine_status", Integer.class)).thenReturn(1);
                    when(rs.getObject("visible_range", Integer.class)).thenReturn(0);
                    when(rs.getInt("user_id")).thenReturn(7);
                    when(rs.getString("title")).thenReturn("兼容性资料");
                    when(rs.getString("description")).thenReturn("旧格式");
                    when(rs.getString("content")).thenReturn("<p>知识正文 😀</p>");
                    when(rs.getString("tag")).thenReturn("Java");
                    when(rs.getObject("reprint_type")).thenReturn(0);
                    doAnswer(call -> List.of(call.<RowMapper<Candidate>>getArgument(1).mapRow(rs, 0)))
                            .when(jdbc).query(anyString(), any(RowMapper.class), eq(1));
                    KnowledgeDocument document = new KnowledgeDocument("help-old", "旧帮助", "旧正文", "v1",
                            "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("Java"));
                    try (var catalog = new KnowledgeCatalog(List.of(document)).lifecycle()) {
                        WikiSourceReader reader = new WikiSourceReader(jdbc, catalog, json);
                        Candidate article = reader.read(new SourceKey(SourceType.ARTICLE, "1"));
                        assertEquals(ARTICLE_JSON, article.rawJson());
                        assertEquals(ARTICLE_HASH, article.fingerprint());
                        when(rs.getString("document_json")).thenReturn(HELP_JSON.substring(16, HELP_JSON.length() - 1));
                        when(rs.getString("version")).thenReturn("v1");
                        doAnswer(call -> List.of(call.<RowMapper<KnowledgeDocument>>getArgument(1).mapRow(rs, 0)))
                                .when(jdbc).query(anyString(), any(RowMapper.class), eq("help-old"));
                        SourceKey key = new SourceKey(SourceType.PLATFORM_HELP, "help-old");
                        assertEquals(HELP_JSON, reader.withAuthority(List.of(key), () -> reader.read(key)).rawJson());
                    }
                    assertEquals("\"1970-01-01 08:00:00\"", json.writeValueAsString(new Date(0)));
                });
    }
}
