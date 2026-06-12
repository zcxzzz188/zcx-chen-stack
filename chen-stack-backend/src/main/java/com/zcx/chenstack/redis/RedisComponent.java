package com.zcx.chenstack.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.vo.SysPermissionVo;
import com.zcx.chenstack.utils.MyThreadFactory;
import com.zcx.chenstack.utils.RedisUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RedisComponent {

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 获取 RedisUtils 工具类实例
     *
     * @return RedisUtils 实例
     */
    public RedisUtils getRedisUtils() {
        return redisUtils;
    }

    /**
     * 获取 StringRedisTemplate 实例
     *
     * @return StringRedisTemplate 实例
     */
    public StringRedisTemplate getStringRedisTemplate() {
        return stringRedisTemplate;
    }

    ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500),
            new MyThreadFactory("RedisComponent"));

    // 保存登录验证码
    public String saveCheckCode(String checkCode) {
        String checkCodeKey = UUID.randomUUID().toString().replace("-", "");
        redisUtils.set(RedisConstants.CheckCode + checkCodeKey, checkCode, RedisConstants.CHECK_CODE_EXPIRE_TIME,
                TimeUnit.SECONDS);
        return checkCodeKey;
    }

    // 获取登录验证码
    public String getCheckCode(String checkCodeKey) {
        return (String) redisUtils.get(RedisConstants.CheckCode + checkCodeKey);
    }

    // 清除登录验证码
    public void cleanCheckCode(String checkCodeKey) {
        redisUtils.del(RedisConstants.CheckCode + checkCodeKey);
    }

    // 保存邮箱验证码
    public void saveEmailCheckCode(String email, String type, String checkCode) {
        redisUtils.set(RedisConstants.EmailCheckCode + type + ":" + email, checkCode,
                RedisConstants.EMAIL_CHECK_CODE_EXPIRE_TIME, TimeUnit.SECONDS);
    }

    // 获取邮箱验证码
    public String getEmailCheckCode(String email, String type) {
        return (String) redisUtils.get(RedisConstants.EmailCheckCode + type + ":" + email);
    }

    // 清除邮箱验证码
    public void cleanEmailCheckCode(String email, String type) {
        redisUtils.del(RedisConstants.EmailCheckCode + type + ":" + email);
    }

    // ==================== 浏览历史相关方法 ====================

    /**
     * 检查用户是否已浏览过文章
     *
     * @param articleId  文章ID
     * @param identifier 用户标识符
     * @return true-已浏览过，false-未浏览过
     */
    public boolean hasReadArticle(Integer articleId, String identifier) {
        String redisKey = RedisConstants.History + articleId;
        return redisUtils.sHasKey(redisKey, identifier);
    }

    /**
     * 记录用户浏览文章
     *
     * @param articleId  文章ID
     * @param identifier 用户标识符
     */
    public void recordArticleRead(Integer articleId, String identifier) {
        String redisKey = RedisConstants.History + articleId;
        redisUtils.sSetAndTime(redisKey, RedisConstants.HISTORY_EXPIRE_TIME, identifier);
    }

    /**
     * 移除用户浏览记录
     *
     * @param articleId  文章ID
     * @param identifier 用户标识符
     */
    public void removeArticleRead(Integer articleId, String identifier) {
        String redisKey = RedisConstants.History + articleId;
        redisUtils.setRemove(redisKey, identifier);
    }

    /**
     * 清除文章的所有浏览记录
     *
     * @param articleId 文章ID
     */
    public void clearArticleReads(Integer articleId) {
        String redisKey = RedisConstants.History + articleId;
        redisUtils.del(redisKey);
    }

    /**
     * 批量清除多篇文章的所有浏览记录
     *
     * @param articleIds 文章ID列表
     */
    public void batchClearArticleReads(List<Integer> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }

        // 构建所有需要删除的Redis key
        String[] redisKeys = articleIds.stream()
                .map(articleId -> RedisConstants.History + articleId)
                .toArray(String[]::new);

        // 批量删除
        redisUtils.del(redisKeys);
    }

    // ==================== 热门文章统计相关方法 ====================

    /**
     * 增加文章热度（访问量+1）
     * 使用Redis ZSet存储，score为访问量，member为文章ID
     *
     * @param articleId 文章ID
     */
    public void incrementArticleHotScore(Integer articleId) {
        executorService.execute(() -> {
            redisUtils.zIncrementScore(RedisConstants.HotArticles7Days, articleId.toString(), 1.0);
            // 设置过期时间为7天
            redisUtils.expire(RedisConstants.HotArticles7Days, RedisConstants.HOT_ARTICLES_EXPIRE_TIME,
                    TimeUnit.SECONDS);
        });
    }

    /**
     * 批量设置文章热度（原子操作，避免并发读取问题）
     * 用于定时任务从数据库同步7天数据
     * <p>
     * 优化策略：
     * 1. 使用临时key进行数据同步，避免清空主key导致的并发读取问题
     * 2. 同步完成后，使用RENAME原子操作切换key
     * 3. 这样用户始终能读取到完整数据
     *
     * @param articleScores Map<文章ID, 综合热度分数>
     */
    public void batchSetArticleHotScore(Map<Integer, Double> articleScores) {
        if (articleScores == null || articleScores.isEmpty()) {
            return;
        }

        executorService.execute(() -> {
            try {
                // 使用临时key进行数据同步
                String tempKey = RedisConstants.HotArticles7Days + ":temp";

                // 先删除可能存在的临时key
                redisUtils.del(tempKey);

                // 批量添加新数据到临时key
                for (Map.Entry<Integer, Double> entry : articleScores.entrySet()) {
                    redisUtils.zAdd(tempKey, entry.getKey().toString(), entry.getValue());
                }

                // 使用RENAME原子操作切换key（旧key会被自动删除）
                // 这样用户在整个过程中都能读取到完整数据
                redisUtils.rename(tempKey, RedisConstants.HotArticles7Days);

                // 设置过期时间为7天
                redisUtils.expire(RedisConstants.HotArticles7Days, RedisConstants.HOT_ARTICLES_EXPIRE_TIME,
                        TimeUnit.SECONDS);

            } catch (Exception e) {
                // 如果出现异常，确保临时key被清理
                String tempKey = RedisConstants.HotArticles7Days + ":temp";
                redisUtils.del(tempKey);
                throw e;
            }
        });
    }

    /**
     * 获取热门文章列表（按访问量倒序）
     *
     * @param topN 获取前N条数据
     * @return 文章ID列表（按热度从高到低排序）
     */
    public List<Integer> getHotArticles(int topN) {
        // 获取分数最高的topN个文章ID（倒序）
        Set<Object> result = redisUtils.zReverseRange(RedisConstants.HotArticles7Days, 0, topN - 1);
        if (result == null || result.isEmpty()) {
            return new ArrayList<>();
        }
        return result.stream()
                .map(obj -> Integer.valueOf(obj.toString()))
                .collect(Collectors.toList());
    }

    /**
     * 获取文章热度分数
     *
     * @param articleId 文章ID
     * @return 热度分数（访问量）
     */
    public Double getArticleHotScore(Integer articleId) {
        return redisUtils.zScore(RedisConstants.HotArticles7Days, articleId.toString());
    }

    /**
     * 批量获取文章热度分数
     * 使用Redis Pipeline优化性能：一次网络请求获取所有分数
     *
     * @param articleIds 文章ID列表
     * @return Map<文章ID, 热度分数>
     */
    public Map<Integer, Double> batchGetArticleHotScore(List<Integer> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return new HashMap<>();
        }

        // 将Integer ID转换为String
        List<String> articleIdStrings = articleIds.stream()
                .map(String::valueOf)
                .collect(Collectors.toList());

        // 使用Pipeline批量获取分数（一次网络往返）
        List<Object> scores = redisUtils.zScoreBatch(RedisConstants.HotArticles7Days, articleIdStrings);

        // 组装结果Map（需要防止空指针和索引越界）
        Map<Integer, Double> resultMap = new HashMap<>();
        if (scores == null || scores.isEmpty()) {
            // Redis中没有数据，返回空Map
            return resultMap;
        }

        // 遍历结果，注意长度可能不一致
        int size = Math.min(articleIds.size(), scores.size());
        for (int i = 0; i < size; i++) {
            Object score = scores.get(i);
            if (score instanceof Double) {
                resultMap.put(articleIds.get(i), (Double) score);
            }
        }
        return resultMap;
    }

    // ==================== WebSocket 用户在线状态相关方法 ====================

    /**
     * 设置用户在线状态
     * 用于用户连接 WebSocket 时标记用户在线
     *
     * @param userId 用户ID
     */
    public void setUserOnlineStatus(Integer userId) {
        redisUtils.set(RedisConstants.USER_ONLINE_STATUS_KEY + userId, true, 30, TimeUnit.MINUTES);
    }

    /**
     * 移除用户在线状态
     * 用于用户断开 WebSocket 时清除在线标记
     *
     * @param userId 用户ID
     */
    public void removeUserOnlineStatus(Integer userId) {
        redisUtils.del(RedisConstants.USER_ONLINE_STATUS_KEY + userId);
    }

    /**
     * 批量获取用户在线状态
     * 用于优化会话列表等场景，避免 N+1 Redis 查询问题
     *
     * @param userIds 用户ID列表
     * @return Map<用户ID, 是否在线>
     */
    public Map<Integer, Boolean> batchGetUserOnlineStatus(List<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new HashMap<>();
        }

        // 构建所有的 Redis key
        List<String> keys = userIds.stream()
                .map(userId -> RedisConstants.USER_ONLINE_STATUS_KEY + userId)
                .collect(Collectors.toList());

        // 批量获取在线状态
        List<Object> results = redisUtils.multiGet(keys);

        // 组装结果 Map
        Map<Integer, Boolean> onlineStatusMap = new HashMap<>();
        for (int i = 0; i < userIds.size() && i < results.size(); i++) {
            Object result = results.get(i);
            boolean isOnline = result != null && (Boolean) result;
            onlineStatusMap.put(userIds.get(i), isOnline);
        }

        return onlineStatusMap;
    }

    // ==================== 管理端首页 Dashboard 统计相关方法 ====================

    /**
     * 获取 Dashboard 统计数据（从 Redis 缓存）
     *
     * @return Dashboard 统计数据，不存在则返回 null
     */
    public Object getDashboardStatistics() {
        return redisUtils.get(RedisConstants.DashboardStatistics);
    }

    /**
     * 设置 Dashboard 统计数据到 Redis 缓存
     * 默认过期时间 5 分钟
     *
     * @param statistics Dashboard 统计数据
     */
    public void setDashboardStatistics(Object statistics) {
        redisUtils.set(RedisConstants.DashboardStatistics, statistics,
                RedisConstants.DASHBOARD_STATISTICS_EXPIRE_TIME, TimeUnit.SECONDS);
    }

    /**
     * 删除 Dashboard 统计数据缓存
     * 当数据发生变化时（如新增用户、文章等）调用此方法
     */
    public void removeDashboardStatistics() {
        redisUtils.del(RedisConstants.DashboardStatistics);
    }

    // ==================== 权限完整列表缓存相关方法 ====================

    /**
     * 获取权限完整列表缓存
     *
     * @return 权限完整列表，不存在或缓存异常时返回 null
     */
    public List<SysPermissionVo> getPermissionListFromCache() {
        try {
            Object cacheValue = redisUtils.get(RedisConstants.PermissionAll);
            if (cacheValue == null) {
                return null;
            }
            if (!(cacheValue instanceof List<?> cacheList)) {
                log.warn("权限完整列表缓存类型异常，redisKey={}", RedisConstants.PermissionAll);
                removePermissionListCache();
                return null;
            }

            List<SysPermissionVo> permissionVos = new ArrayList<>();
            for (Object item : cacheList) {
                if (item instanceof SysPermissionVo permissionVo) {
                    permissionVos.add(permissionVo);
                    continue;
                }
                try {
                    SysPermissionVo permissionVo = objectMapper.convertValue(item, SysPermissionVo.class);
                    if (permissionVo == null) {
                        log.warn("权限完整列表缓存元素转换结果为空，redisKey={}", RedisConstants.PermissionAll);
                        removePermissionListCache();
                        return null;
                    }
                    permissionVos.add(permissionVo);
                } catch (IllegalArgumentException e) {
                    log.warn("权限完整列表缓存元素转换失败，redisKey={}，elementType={}",
                            RedisConstants.PermissionAll, item == null ? "null" : item.getClass().getName(), e);
                    removePermissionListCache();
                    return null;
                }
            }
            return permissionVos;
        } catch (Exception e) {
            log.warn("读取权限完整列表缓存失败，将回源数据库，redisKey={}", RedisConstants.PermissionAll, e);
            removePermissionListCache();
            return null;
        }
    }

    /**
     * 设置权限完整列表缓存
     *
     * @param permissions 完整权限列表
     */
    public void setPermissionListToCache(List<SysPermissionVo> permissions) {
        try {
            redisUtils.set(RedisConstants.PermissionAll, permissions,
                    RedisConstants.PERMISSION_ALL_EXPIRE_TIME, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写入权限完整列表缓存失败，redisKey={}", RedisConstants.PermissionAll, e);
        }
    }

    /**
     * 删除权限完整列表缓存
     */
    public void removePermissionListCache() {
        try {
            redisUtils.del(RedisConstants.PermissionAll);
        } catch (Exception e) {
            log.warn("删除权限完整列表缓存失败，redisKey={}", RedisConstants.PermissionAll, e);
        }
    }

    // ==================== 用户详情缓存相关方法 ====================

    /**
     * 获取用户详情缓存（包含角色、菜单、权限信息）
     *
     * @param userId 用户 ID
     * @return 用户详情对象，不存在则返回 null
     */
    public SysUser getUserDetailFromCache(Integer userId) {
        return (SysUser) redisUtils.get(RedisConstants.UserDetail + userId);
    }

    /**
     * 设置用户详情缓存（包含角色、菜单、权限信息）
     * 默认过期时间 30 分钟
     *
     * @param user 用户详情对象
     */
    public void setUserDetailToCache(SysUser user) {
        redisUtils.set(RedisConstants.UserDetail + user.getId(), user,
                RedisConstants.USER_DETAIL_EXPIRE_TIME, TimeUnit.SECONDS);
    }

    /**
     * 删除用户详情缓存
     * 当用户信息发生变化时（如角色变更、菜单调整等）调用此方法
     *
     * @param userId 用户 ID
     */
    public void removeUserDetail(Integer userId) {
        redisUtils.del(RedisConstants.UserDetail + userId);
    }

    /**
     * 清除指定角色下所有用户的详情缓存
     * 当角色权限发生变更时调用此方法，清除该角色下所有用户的缓存
     *
     * @param userIds 受影响的用户 ID 列表
     */
    public void removeUserDetailsByRole(List<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        for (Integer userId : userIds) {
            redisUtils.del(RedisConstants.UserDetail + userId);
        }
    }

    // ==================== 热门搜索相关方法 ====================

    /**
     * 记录搜索关键词到 Redis ZSet
     * 格式：key = "hot_searches", member = 关键词，score = 搜索次数
     * 使用异步线程执行，避免阻塞主线程
     *
     * @param keyword 搜索关键词
     */
    public void recordSearchKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        executorService.execute(() -> {
            String redisKey = "hot_searches";
            // 使用 ZSet 的 incrementScore 方法，如果 member 不存在会自动创建并设置 score 为 1
            stringRedisTemplate.opsForZSet().incrementScore(redisKey, keyword.trim(), 1.0);
        });
    }

    /**
     * 从 Redis ZSet 获取热门搜索列表（按搜索次数降序）
     *
     * @param limit 返回数量限制
     * @return 热门搜索列表，每个元素包含 keyword 和 count
     */
    public List<Map<String, Object>> getHotSearches(int limit) {
        String redisKey = "hot_searches";
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(redisKey, 0, limit - 1);

        List<Map<String, Object>> result = new ArrayList<>();
        if (typedTuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
                Map<String, Object> item = new HashMap<>();
                item.put("keyword", tuple.getValue());
                item.put("count", tuple.getScore().longValue());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 清除指定搜索关键词的热度记录
     *
     * @param keyword 搜索关键词
     */
    public void clearSearchKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return;
        }
        String redisKey = "hot_searches";
        stringRedisTemplate.opsForZSet().remove(redisKey, keyword.trim());
    }

    /**
     * 清除所有热门搜索记录
     */
    public void clearAllHotSearches() {
        String redisKey = "hot_searches";
        stringRedisTemplate.delete(redisKey);
    }

}
