package com.zcx.chenstack.controller;

import com.zcx.chenstack.aspect.OperationLog;
import com.zcx.chenstack.aspect.RateLimit;
import com.zcx.chenstack.domain.enums.OperationTypeEnum;
import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.domain.vo.ContentActivityVo;
import com.zcx.chenstack.domain.vo.DashboardAllVo;
import com.zcx.chenstack.domain.vo.DashboardStatisticsVo;
import com.zcx.chenstack.domain.vo.ExamineCountVo;
import com.zcx.chenstack.domain.vo.InteractionTrendVo;
import com.zcx.chenstack.domain.vo.UserDistributionVo;
import com.zcx.chenstack.domain.vo.VisitorTrendVo;
import com.zcx.chenstack.domain.vo.WeeklyTrendVo;
import com.zcx.chenstack.service.DashboardService;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端首页 Dashboard 控制器
 *
 * @author zcx
 * @since 2026-03-15
 */
@RateLimit(30)
@Validated
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    @Resource
    private DashboardService dashboardService;

    /**
     * 获取管理端首页统计数据
     * 仅管理员可访问
     *
     * @param trendDays 访客趋势天数，默认 7 天（可选：7/14/30）
     * @return Dashboard 统计数据
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取首页统计数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/statistics")
    public Result<DashboardStatisticsVo> getStatistics(
            @RequestParam(defaultValue = "7") @NotNull(message = "趋势天数不能为空")
            @Min(value = 1, message = "趋势天数不能小于 1")
            @Max(value = 365, message = "趋势天数不能超过 365") Integer trendDays) {
        DashboardStatisticsVo statistics = dashboardService.getDashboardStatistics(trendDays);
        return Result.success(statistics);
    }

    /**
     * 刷新 Dashboard 缓存
     * 仅管理员可访问
     *
     * @return 操作结果
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.OTHER, description = "管理员刷新首页缓存")
    @PreAuthorize("@adminSecurity.isAdmin() and hasAuthority('admin:dashboard:refresh')")
    @PostMapping("/refresh")
    public Result<Void> refreshCache() {
        dashboardService.refreshDashboardCache();
        return Result.success();
    }

    /**
     * 获取近7天文章和用户增长趋势
     * 仅管理员可访问
     *
     * @return 周趋势数据列表
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取周趋势数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/weekly-trend")
    public Result<List<WeeklyTrendVo>> getWeeklyTrend() {
        List<WeeklyTrendVo> trend = dashboardService.getWeeklyTrend();
        return Result.success(trend);
    }

    /**
     * 获取用户角色分布
     * 仅管理员可访问
     *
     * @return 用户分布数据列表
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取用户分布数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/user-distribution")
    public Result<List<UserDistributionVo>> getUserDistribution() {
        List<UserDistributionVo> distribution = dashboardService.getUserDistribution();
        return Result.success(distribution);
    }

    /**
     * 获取内容模块活跃度
     * 仅管理员可访问
     *
     * @return 内容活跃度数据列表
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取内容活跃度数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/content-activity")
    public Result<List<ContentActivityVo>> getContentActivity() {
        List<ContentActivityVo> activity = dashboardService.getContentActivity();
        return Result.success(activity);
    }

    /**
     * 获取待审核数量统计
     * 仅管理员可访问
     *
     * @return 待审核数量（文章、评论、图片）
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取待审核数量")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/examine-count")
    public Result<ExamineCountVo> getExamineCount() {
        ExamineCountVo count = dashboardService.getExamineCount();
        return Result.success(count);
    }

    /**
     * 获取访客趋势数据
     * 仅管理员可访问
     *
     * @param days 天数（可选：7/14/30）
     * @return 访客趋势数据列表
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取访客趋势数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/visitor-trend")
    public Result<List<VisitorTrendVo>> getVisitorTrend(
            @RequestParam(defaultValue = "7") @NotNull(message = "天数不能为空")
            @Min(value = 1, message = "天数不能小于 1")
            @Max(value = 365, message = "天数不能超过 365") Integer days) {
        List<VisitorTrendVo> trend = dashboardService.getVisitorTrendByDays(days);
        return Result.success(trend);
    }

    /**
     * 获取近7天互动趋势（评论数、点赞数、收藏数）
     * 仅管理员可访问
     *
     * @return 互动趋势数据列表
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取互动趋势数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/interaction-trend")
    public Result<List<InteractionTrendVo>> getInteractionTrend() {
        List<InteractionTrendVo> trend = dashboardService.getInteractionTrend();
        return Result.success(trend);
    }

    /**
     * 获取管理端首页完整数据（聚合接口）
     * 一次性返回所有 Dashboard 数据，减少前端请求次数
     * 仅管理员可访问
     *
     * @param trendDays 访客趋势天数，默认 7 天（可选：7/14/30）
     * @return 完整的 Dashboard 数据
     */
    @OperationLog(module = "Dashboard", type = OperationTypeEnum.GET, description = "管理员获取首页完整数据")
    @PreAuthorize("hasAuthority('backend:dashboard:list')")
    @GetMapping("/all")
    public Result<DashboardAllVo> getDashboardAll(
            @RequestParam(defaultValue = "7") @NotNull(message = "趋势天数不能为空")
            @Min(value = 1, message = "趋势天数不能小于 1")
            @Max(value = 365, message = "趋势天数不能超过 365") Integer trendDays) {
        DashboardAllVo allData = dashboardService.getDashboardAll(trendDays);
        return Result.success(allData);
    }

}
