package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.entity.SysVisitorLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访客记录 Mapper 接口
 *
 * @author zcx
 * @since 2025-10-06
 */
@Mapper
public interface SysVisitorLogMapper extends BaseMapper<SysVisitorLog> {
}

