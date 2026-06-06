package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.entity.ArticleFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章收藏记录 Mapper 接口
 *
 * @author zcx
 * @since 2026-03-29
 */
@Mapper
public interface ArticleFavoriteMapper extends BaseMapper<ArticleFavorite> {

}
