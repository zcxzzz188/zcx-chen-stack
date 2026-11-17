package com.zcx.chenstack.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record KnowledgeList<T>(List<T> items, long total) {
    public record Filter(@Min(1) @Max(100000) int page, @Min(1) @Max(50) int size,
                         @Size(max = 160) String q,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        int offset() {
            if (from != null && to != null && from.isAfter(to)) {
                throw KnowledgeDocException.bad("KNOWLEDGE_DATE_RANGE", "开始时间不能晚于结束时间");
            }
            return (page - 1) * size;
        }

        String keyword() {
            return q == null ? "" : q.strip();
        }
    }

    static <T> KnowledgeList<T> query(JdbcTemplate jdbc, Filter filter, String from, String sort,
                                     RowMapper<T> mapper, Object... parameters) {
        int offset = filter.offset();
        List<Object> args = new ArrayList<>(Arrays.asList(parameters));
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
        args.add(filter.size());
        args.add(offset);
        List<T> rows = jdbc.query("SELECT *" + from + " ORDER BY " + sort + " LIMIT ? OFFSET ?",
                mapper, args.toArray());
        return new KnowledgeList<>(rows, total);
    }
}
