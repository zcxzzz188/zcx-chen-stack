package com.zcx.chenstack.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Structural Mapper regression; no database or HTTP behavior is simulated here.
class HistoryVisibilityTest {
    private static final String MAPPER = "mapper/HistoryMapper.xml";

    private MappedStatement statement() throws Exception {
        Configuration configuration = new Configuration();
        try (Reader reader = Resources.getResourceAsReader(MAPPER)) {
            new XMLMapperBuilder(reader, configuration, MAPPER, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.zcx.chenstack.mapper.HistoryMapper.getUserHistoryList");
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    void visibilityAndExistingStateFiltersApplyBeforePagination() throws Exception {
        String sql = statement().getBoundSql(Map.of("userId", 7)).getSql();
        String where = sql.substring(sql.indexOf("WHERE h.user_id"), sql.lastIndexOf("ORDER BY"));
        assertEquals(normalize("""
                WHERE h.user_id = ? AND h.is_deleted = 0
                  AND a.visible_range IN (0, 1, 2)
                  AND (a.user_id = ? OR a.visible_range = 0
                       OR (a.visible_range = 2 AND EXISTS (
                           SELECT 1 FROM follow f
                           WHERE f.follower_id = ? AND f.followed_id = a.user_id
                       )))
                """), normalize(where));
        for (String required : List.of("a.examine_status = 1", "a.edit_status = 0", "a.is_deleted = 0", "u.is_deleted = 0")) {
            assertTrue(normalize(sql).contains(normalize(required)), required);
        }
        assertFalse(normalize(sql).contains("limit"));
    }

    @Test
    void historyOwnerArticleOwnerAndFollowChecksBindTheSameViewer() throws Exception {
        MappedStatement statement = statement();
        Map<String, Object> parameters = Map.of("userId", 7);
        BoundSql bound = statement.getBoundSql(parameters);
        assertEquals(List.of("userId", "userId", "userId"),
                bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
        PreparedStatement prepared = mock(PreparedStatement.class);
        ParameterHandler handler = new DefaultParameterHandler(statement, parameters, bound);
        handler.setParameters(prepared);
        verify(prepared).setInt(1, 7);
        verify(prepared).setInt(2, 7);
        verify(prepared).setInt(3, 7);
        verifyNoMoreInteractions(prepared);
    }

    @Test
    void paginationCountRetainsTheSameVisibilityAndHistoryPredicate() throws Exception {
        String sql = statement().getBoundSql(Map.of("userId", 7)).getSql();
        String where = sql.substring(sql.indexOf("WHERE h.user_id"), sql.lastIndexOf("ORDER BY"));
        String count = new PaginationInnerInterceptor(DbType.MYSQL).autoCountSql(new Page<>(1, 2), sql);
        assertTrue(normalize(count).contains(normalize(where)), count);
        assertFalse(normalize(count).contains("orderby"), count);
        assertTrue(normalize(count).contains("count(*)"), count);
        for (String required : List.of("a.examine_status = 1", "a.edit_status = 0", "a.is_deleted = 0", "u.is_deleted = 0")) {
            assertTrue(normalize(count).contains(normalize(required)), required);
        }
    }
}
