package com.zcx.chenstack.mapper;

import com.zcx.chenstack.domain.entity.Column;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author zcx
 * @since 2025-08-26
 */
public interface ColumnMapper extends BaseMapper<Column> {
    @Select({"<script>",
            "SELECT c.id, COUNT(DISTINCT a.id) AS article_count FROM `column` c",
            "LEFT JOIN article_column ac ON ac.column_id = c.id AND ac.is_deleted = 0",
            "LEFT JOIN article a ON a.id = ac.article_id AND a.user_id = c.user_id",
            "AND a.is_deleted = 0 AND a.examine_status = 1 AND a.edit_status = 0 AND a.visible_range = 0",
            "WHERE c.is_deleted = 0 AND c.id IN",
            "<foreach collection='columnIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "GROUP BY c.id", "</script>"})
    List<Column> selectPublicArticleCounts(@Param("columnIds") List<Integer> columnIds);
}
