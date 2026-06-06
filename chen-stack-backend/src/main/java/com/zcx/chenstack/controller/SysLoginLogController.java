package com.zcx.chenstack.controller;

import com.zcx.chenstack.aspect.OperationLog;
import com.zcx.chenstack.aspect.RateLimit;
import com.zcx.chenstack.domain.dto.SysLoginLogQueryDto;
import com.zcx.chenstack.domain.vo.SysLoginLogVo;
import com.zcx.chenstack.domain.enums.OperationTypeEnum;
import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.service.SysLoginLogService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author zcx
 * @since 2025-10-06
 */
@RateLimit(30)
@Validated
@RestController
@RequestMapping("/loginLog")
public class SysLoginLogController {

    @Resource
    private SysLoginLogService sysLoginLogService;

    /**
     * 查询所有登录日志（按时间倒序）
     *
     * @return 登录日志列表
     */
    @OperationLog(module = "登录日志管理", type = OperationTypeEnum.SELECT, description = "管理员获取登录日志列表")
    @PreAuthorize("hasAuthority('system:loginLog:list')")
    @GetMapping("/admin/list")
    public Result<PageVo<List<SysLoginLogVo>>> getLoginLogList(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码不能小于 1") Integer pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页大小不能小于 1")
            @Max(value = 100, message = "每页大小不能超过 100") Integer pageSize) {
        PageVo<List<SysLoginLogVo>> result = sysLoginLogService.getLoginLogList(pageNum, pageSize);
        return Result.success(result);
    }

    /**
     * 搜索登录日志
     *
     * @param queryDto 查询条件
     * @return 登录日志列表
     */
    @OperationLog(module = "登录日志管理", type = OperationTypeEnum.SEARCH, description = "管理员搜索登录日志")
    @PreAuthorize("hasAuthority('system:loginLog:search')")
    @PostMapping("/admin/search")
    public Result<PageVo<List<SysLoginLogVo>>> searchLoginLog(@RequestBody @Valid SysLoginLogQueryDto queryDto) {
        PageVo<List<SysLoginLogVo>> result = sysLoginLogService.searchLoginLog(queryDto);
        return Result.success(result);
    }

    /**
     * 批量删除登录日志
     *
     * @param ids 登录日志ID列表
     * @return 操作结果
     */
    @OperationLog(module = "登录日志管理", type = OperationTypeEnum.DELETE, description = "管理员批量删除登录日志")
    @PreAuthorize("hasAuthority('system:loginLog:delete')")
    @DeleteMapping("/admin/batch")
    public Result<Void> deleteLoginLogs(@RequestBody @NotEmpty List<Integer> ids) {
        sysLoginLogService.deleteLoginLogs(ids);
        return Result.success();
    }
}
