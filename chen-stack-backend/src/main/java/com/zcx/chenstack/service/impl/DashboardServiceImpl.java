package com.zcx.chenstack.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.entity.Article;
import com.zcx.chenstack.domain.entity.ArticleFavorite;
import com.zcx.chenstack.domain.entity.Comment;
import com.zcx.chenstack.domain.entity.Favorite;
import com.zcx.chenstack.domain.entity.Like;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.entity.SysLoginLog;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.entity.SysUserRole;
import com.zcx.chenstack.domain.vo.ContentActivityVo;
import com.zcx.chenstack.domain.vo.DashboardAllVo;
import com.zcx.chenstack.domain.vo.DashboardStatisticsVo;
import com.zcx.chenstack.domain.vo.ExamineCountVo;
import com.zcx.chenstack.domain.vo.InteractionTrendVo;
import com.zcx.chenstack.domain.vo.UserDistributionVo;
import com.zcx.chenstack.domain.vo.VisitorTrendVo;
import com.zcx.chenstack.domain.vo.WeeklyTrendVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.ArticleFavoriteMapper;
import com.zcx.chenstack.mapper.ArticleMapper;
import com.zcx.chenstack.mapper.CommentMapper;
import com.zcx.chenstack.mapper.FavoriteMapper;
import com.zcx.chenstack.mapper.LikeMapper;
import com.zcx.chenstack.mapper.PhotoMapper;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.mapper.SysLoginLogMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.mapper.SysUserRoleMapper;
import com.zcx.chenstack.redis.RedisComponent;
import com.zcx.chenstack.service.ArticleService;
import com.zcx.chenstack.service.DashboardService;
import com.zcx.chenstack.service.SysVisitorLogService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 管理端首页 Dashboard 统计服务实现
 *
 * @author zcx
 * @since 2026-03-15
 */
@Service
@Slf4j
public class DashboardServiceImpl implements DashboardService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private SysUserRoleMapper sysUserRoleMapper;

    @Resource
    private ArticleMapper articleMapper;

    @Resource
    private CommentMapper commentMapper;

    @Resource
    private FavoriteMapper favoriteMapper;

    @Resource
    private PrivateMessageMapper privateMessageMapper;

    @Resource
    private ArticleService articleService;

    @Resource
    private SysVisitorLogService visitorLogService;

    @Resource
    private RedisComponent redisComponent;

    @Resource
    private SysLoginLogMapper sysLoginLogMapper;

    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private LikeMapper likeMapper;

    @Resource
    private ArticleFavoriteMapper articleFavoriteMapper;

    @Override
    public DashboardStatisticsVo getDashboardStatistics() {
        return getDashboardStatistics(7);
    }

    @Override
    public DashboardStatisticsVo getDashboardStatistics(Integer trendDays) {
        // 1. 先尝试从 Redis 缓存获取
        Object cached = redisComponent.getDashboardStatistics();
        if (cached instanceof DashboardStatisticsVo) {
            DashboardStatisticsVo cachedVo = (DashboardStatisticsVo) cached;
            // 如果缓存中的趋势天数与请求的不一致，需要重新获取趋势数据
            if (cachedVo.getVisitorTrend() != null && cachedVo.getVisitorTrend().size() == trendDays) {
                return cachedVo;
            }
        }

        // 2. Redis 缓存未命中或趋势天数不匹配，从数据库查询并组装数据
        DashboardStatisticsVo result = new DashboardStatisticsVo();

        // 2.1 获取用户总数
        result.setUserTotalCount(getUserTotalCount());

        // 2.2 获取文章统计数据
        result.setArticleStatistics(articleService.getAdminStatistics());

        // 2.3 获取今日访问量和总访问量
        result.setTodayVisits(visitorLogService.getTodayVisitorCount());
        result.setTotalVisits(visitorLogService.getTotalVisitorCount());

        // 2.4 获取今日活跃用户数（当日登录的不同用户数）
        result.setTodayActiveUserCount(getTodayActiveUserCount());

        // 2.5 获取访客趋势数据
        result.setVisitorTrend(visitorLogService.getVisitorTrend(trendDays));

        // 3. 写入 Redis 缓存
        redisComponent.setDashboardStatistics(result);

        return result;
    }

    @Override
    public void refreshDashboardCache() {
        redisComponent.removeDashboardStatistics();
    }

    @Override
    public List<WeeklyTrendVo> getWeeklyTrend() {
        // 使用 CompletableFuture 并行查询文章和用户数据，减少串行等待时间
        CompletableFuture<List<Integer>> articleFuture = CompletableFuture.supplyAsync(() -> {
            List<Integer> articleCounts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(Article::getExamineStatus, 1)
                      .ge(Article::getCreateTime, date.atStartOfDay())
                      .lt(Article::getCreateTime, date.plusDays(1).atStartOfDay());
                articleCounts.add(Math.toIntExact(articleMapper.selectCount(wrapper)));
            }
            return articleCounts;
        });

        CompletableFuture<List<Integer>> userFuture = CompletableFuture.supplyAsync(() -> {
            List<Integer> userCounts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
                wrapper.ge(SysUser::getCreateTime, date.atStartOfDay())
                      .lt(SysUser::getCreateTime, date.plusDays(1).atStartOfDay());
                userCounts.add(Math.toIntExact(sysUserMapper.selectCount(wrapper)));
            }
            return userCounts;
        });

        // 等待所有查询完成并组装结果
        try {
            CompletableFuture.allOf(articleFuture, userFuture).join();
            List<Integer> articleCounts = articleFuture.get();
            List<Integer> userCounts = userFuture.get();

            List<WeeklyTrendVo> result = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = LocalDate.now().minusDays(6 - i);
                result.add(new WeeklyTrendVo()
                    .setDate(date.toString())
                    .setArticleCount(articleCounts.get(i))
                    .setUserCount(userCounts.get(i)));
            }
            return result;
        } catch (Exception e) {
            log.error("获取周趋势数据失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<UserDistributionVo> getUserDistribution() {
        List<UserDistributionVo> result = new ArrayList<>();

        // 管理员 role_id=1，其余计入普通用户
        Long totalUserCount = sysUserMapper.selectCount(null);
        Long adminCount = sysUserRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
            .eq(SysUserRole::getRoleId, 1));

        result.add(new UserDistributionVo().setType("普通用户").setCount(totalUserCount - adminCount));
        result.add(new UserDistributionVo().setType("管理员").setCount(adminCount));

        return result;
    }

    /**
     * 获取用户总数
     *
     * @return 用户总数
     */
    private Long getUserTotalCount() {
        try {
            return (long) sysUserMapper.selectCount(null);
        } catch (Exception e) {
            log.error("获取用户总数失败", e);
            throw new BlogException(BlogConstants.SystemInternalError);
        }
    }

    /**
     * 获取今日活跃用户数（当日登录的不同用户数）
     *
     * @return 今日活跃用户数
     */
    private Long getTodayActiveUserCount() {
        try {
            LocalDate today = LocalDate.now();
            // 查询今日登录且状态为成功的不同用户数
            LambdaQueryWrapper<SysLoginLog> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysLoginLog::getStatus, 0)
                  .ge(SysLoginLog::getLoginTime, today.atStartOfDay())
                  .lt(SysLoginLog::getLoginTime, today.plusDays(1).atStartOfDay());
            // 统计不同 userId 的数量
            return sysLoginLogMapper.selectCount(wrapper);
        } catch (Exception e) {
            log.error("获取今日活跃用户数失败", e);
            return 0L;
        }
    }

    @Override
    public List<ContentActivityVo> getContentActivity() {
        List<ContentActivityVo> result = new ArrayList<>();

        // 获取各类数据总量或当日增量
        LocalDate today = LocalDate.now();

        // 文章活跃度：当日新增文章数
        LambdaQueryWrapper<Article> articleWrapper = new LambdaQueryWrapper<>();
        articleWrapper.ge(Article::getCreateTime, today.atStartOfDay());
        long articleCount = articleMapper.selectCount(articleWrapper);

        // 评论活跃度：当日新增评论数
        LambdaQueryWrapper<Comment> commentWrapper = new LambdaQueryWrapper<>();
        commentWrapper.ge(Comment::getCreateTime, today.atStartOfDay());
        long commentCount = commentMapper.selectCount(commentWrapper);

        // 收藏活跃度：当日新增收藏夹数
        LambdaQueryWrapper<Favorite> favoriteWrapper = new LambdaQueryWrapper<>();
        favoriteWrapper.ge(Favorite::getCreateTime, today.atStartOfDay());
        long favoriteCount = favoriteMapper.selectCount(favoriteWrapper);

        // 私信活跃度：当日新增私信数
        LambdaQueryWrapper<PrivateMessage> messageWrapper = new LambdaQueryWrapper<>();
        messageWrapper.ge(PrivateMessage::getCreateTime, today.atStartOfDay());
        long messageCount = privateMessageMapper.selectCount(messageWrapper);

        // 归一化到 0-100 分（取各类最大值作为基准）
        long maxCount = Math.max(articleCount, Math.max(commentCount,
            Math.max(favoriteCount, messageCount)));
        if (maxCount == 0) maxCount = 1; // 避免除零

        result.add(new ContentActivityVo().setItem("文章").setScore(normalize(articleCount, maxCount)));
        result.add(new ContentActivityVo().setItem("评论").setScore(normalize(commentCount, maxCount)));
        result.add(new ContentActivityVo().setItem("收藏").setScore(normalize(favoriteCount, maxCount)));
        result.add(new ContentActivityVo().setItem("私信").setScore(normalize(messageCount, maxCount)));

        return result;
    }

    /**
     * 归一化评分（0-100）
     *
     * @param count 当前数量
     * @param max 最大数量
     * @return 归一化评分
     */
    private int normalize(long count, long max) {
        return (int) Math.min(100, (count * 100) / max);
    }

    @Override
    public ExamineCountVo getExamineCount() {
        ExamineCountVo result = new ExamineCountVo();

        // 待审核文章数（examineStatus = 0）
        LambdaQueryWrapper<Article> articleWrapper = new LambdaQueryWrapper<>();
        articleWrapper.eq(Article::getExamineStatus, 0);
        result.setArticleCount(articleMapper.selectCount(articleWrapper));

        // 待审核评论数（examineStatus = 0）
        LambdaQueryWrapper<Comment> commentWrapper = new LambdaQueryWrapper<>();
        commentWrapper.eq(Comment::getExamineStatus, 0);
        result.setCommentCount(commentMapper.selectCount(commentWrapper));

        // 待审核图片数（examineStatus = 0）
        LambdaQueryWrapper<Photo> photoWrapper = new LambdaQueryWrapper<>();
        photoWrapper.eq(Photo::getExamineStatus, 0);
        result.setPhotoCount(photoMapper.selectCount(photoWrapper));

        return result;
    }

    @Override
    public List<VisitorTrendVo> getVisitorTrendByDays(Integer days) {
        return visitorLogService.getVisitorTrend(days);
    }

    @Override
    public List<InteractionTrendVo> getInteractionTrend() {
        // 使用 CompletableFuture 并行查询评论、点赞、收藏数据
        CompletableFuture<List<Integer>> commentFuture = CompletableFuture.supplyAsync(() -> {
            List<Integer> counts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
                wrapper.ge(Comment::getCreateTime, date.atStartOfDay())
                     .lt(Comment::getCreateTime, date.plusDays(1).atStartOfDay());
                counts.add(Math.toIntExact(commentMapper.selectCount(wrapper)));
            }
            return counts;
        });

        CompletableFuture<List<Integer>> likeFuture = CompletableFuture.supplyAsync(() -> {
            List<Integer> counts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LambdaQueryWrapper<Like> wrapper = new LambdaQueryWrapper<>();
                wrapper.ge(Like::getCreateTime, date.atStartOfDay())
                      .lt(Like::getCreateTime, date.plusDays(1).atStartOfDay())
                      .eq(Like::getType, 0);
                counts.add(Math.toIntExact(likeMapper.selectCount(wrapper)));
            }
            return counts;
        });

        CompletableFuture<List<Integer>> favoriteFuture = CompletableFuture.supplyAsync(() -> {
            List<Integer> counts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate date = LocalDate.now().minusDays(i);
                LambdaQueryWrapper<ArticleFavorite> wrapper = new LambdaQueryWrapper<>();
                wrapper.ge(ArticleFavorite::getCreateTime, date.atStartOfDay())
                     .lt(ArticleFavorite::getCreateTime, date.plusDays(1).atStartOfDay());
                counts.add(Math.toIntExact(articleFavoriteMapper.selectCount(wrapper)));
            }
            return counts;
        });

        // 等待所有查询完成并组装结果
        try {
            CompletableFuture.allOf(commentFuture, likeFuture, favoriteFuture).join();
            List<Integer> commentCounts = commentFuture.get();
            List<Integer> likeCounts = likeFuture.get();
            List<Integer> favoriteCounts = favoriteFuture.get();

            List<InteractionTrendVo> result = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                LocalDate date = LocalDate.now().minusDays(6 - i);
                result.add(new InteractionTrendVo()
                    .setDate(date.toString())
                    .setCommentCount(commentCounts.get(i))
                    .setLikeCount(likeCounts.get(i))
                    .setFavoriteCount(favoriteCounts.get(i)));
            }
            return result;
        } catch (Exception e) {
            log.error("获取互动趋势数据失败", e);
            return new ArrayList<>();
        }
    }

    @Override
    public DashboardAllVo getDashboardAll(Integer trendDays) {
        final int days = (trendDays == null) ? 7 : trendDays;

        // 使用 CompletableFuture 并行获取所有数据
        CompletableFuture<DashboardStatisticsVo> statisticsFuture = CompletableFuture.supplyAsync(() ->
            getDashboardStatistics(days)
        );

        CompletableFuture<ExamineCountVo> examineCountFuture = CompletableFuture.supplyAsync(() ->
            getExamineCount()
        );

        CompletableFuture<List<WeeklyTrendVo>> weeklyTrendFuture = CompletableFuture.supplyAsync(() ->
            getWeeklyTrend()
        );

        CompletableFuture<List<InteractionTrendVo>> interactionTrendFuture = CompletableFuture.supplyAsync(() ->
            getInteractionTrend()
        );

        // 等待所有查询完成
        try {
            CompletableFuture.allOf(
                statisticsFuture,
                examineCountFuture,
                weeklyTrendFuture,
                interactionTrendFuture
            ).join();

            // 组装结果
            DashboardAllVo result = new DashboardAllVo();
            result.setStatistics(statisticsFuture.get());
            result.setExamineCount(examineCountFuture.get());
            result.setWeeklyTrend(weeklyTrendFuture.get());
            result.setInteractionTrend(interactionTrendFuture.get());
            return result;
        } catch (Exception e) {
            log.error("获取完整 Dashboard 数据失败", e);
            return new DashboardAllVo();
        }
    }

}
