package com.zcx.chenstack.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 管理端首页控制台统计数据 VO
 *
 * @author zcx
 * @since 2026-03-15
 */
@Data
@Accessors(chain = true)
public class DashboardStatisticsVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户总数
     */
    private Long userTotalCount;

    /**
     * 今日活跃用户数（当日登录的不同用户数）
     */
    private Long todayActiveUserCount;

    /**
     * 今日新增用户数
     */
    private Long todayNewUserCount;

    /**
     * 文章统计数据
     */
    private ArticleStatisticsVo articleStatistics;

    /**
     * 今日新增文章数
     */
    private Long todayNewArticleCount;

    /**
     * 今日访问量
     */
    private Long todayVisits;

    /**
     * 总访问量
     */
    private Long totalVisits;

    /**
     * 访客趋势数据（最近 N 天）
     */
    private List<VisitorTrendVo> visitorTrend;

}
