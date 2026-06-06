package com.zcx.chenstack.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zcx.chenstack.domain.entity.SysOperationlog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper 接口
 *
 * @author zcx
 * @since 2025-07-08
 */
@Mapper
public interface SysOperationlogMapper extends BaseMapper<SysOperationlog> {
}
