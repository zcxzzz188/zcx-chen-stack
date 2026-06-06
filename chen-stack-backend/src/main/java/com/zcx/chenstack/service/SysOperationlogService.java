package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysOperationlogQueryDto;
import com.zcx.chenstack.domain.entity.SysOperationlog;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysOperationlogListVo;
import com.zcx.chenstack.domain.vo.SysOperationlogVo;

import java.util.List;

/**
 * 操作日志 Service 接口
 *
 * @author zcx
 * @since 2025-07-08
 */
public interface SysOperationlogService extends IService<SysOperationlog> {

    /**
     * 插入操作日志记录
     *
     * @param operationlog 操作日志信息
     */
    void insertOperationlogRecord(SysOperationlog operationlog);

    /**
     * 查询所有操作日志（按时间倒序）
     *
     * @return 操作日志列表（精简版）
     */
    PageVo<List<SysOperationlogListVo>> getOperationlogList(Integer pageNum, Integer pageSize);

    /**
     * 搜索操作日志
     *
     * @param queryDto 查询条件
     * @return 操作日志列表（精简版）
     */
    PageVo<List<SysOperationlogListVo>> searchOperationlog(SysOperationlogQueryDto queryDto);

    /**
     * 批量删除操作日志
     *
     * @param ids 操作日志 ID 列表
     */
    void deleteOperationlogs(List<Integer> ids);

    /**
     * 获取操作日志详情
     *
     * @param id 操作日志 ID
     * @return 操作日志详情
     */
    SysOperationlogVo getOperationlogDetail(Integer id);
}
