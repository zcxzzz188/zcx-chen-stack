package com.zcx.chenstack.domain.constants;

/**
 * @author zcx
 * @since 2025-06-30
 */
public class RedisConstants {

    public static final String RedisKeyPrefix = "chen_stack:";

    public static final String CheckCode = RedisKeyPrefix + "CheckCode:";

    public static final String EmailCheckCode = RedisKeyPrefix + "EmailCheckCode:";

    /**
     * 登录验证码过期时间（5分钟，单位：秒）
     */
    public static final long CHECK_CODE_EXPIRE_TIME = 5 * 60;

    /**
     * 邮箱验证码过期时间（5分钟，单位：秒）
     */
    public static final long EMAIL_CHECK_CODE_EXPIRE_TIME = 5 * 60;

    /**
     * 浏览历史缓存键
     * 格式：chen_stack:History:文章ID
     * 存储结构：Set，存储已浏览过该文章的用户ID或IP地址
     */
    public static final String History = RedisKeyPrefix + "History:";

    /**
     * 浏览记录Redis缓存过期时间（24小时，单位：秒）
     */
    public static final long HISTORY_EXPIRE_TIME = 24 * 60 * 60;

    /**
     * 限流缓存键前缀
     * 格式：chen_stack:RateLimit:方法名:用户标识
     */
    public static final String RateLimit = RedisKeyPrefix + "RateLimit:";

    /**
     * 热门文章缓存键（近7天访问量排行）
     * 格式：chen_stack:HotArticles:7Days
     * 存储结构：ZSet，score为访问量，member为文章ID
     * 用于快速查询热门文章列表
     */
    public static final String HotArticles7Days = RedisKeyPrefix + "HotArticles:7Days";

    /**
     * 热门文章缓存过期时间（7天，单位：秒）
     */
    public static final long HOT_ARTICLES_EXPIRE_TIME = 7 * 24 * 60 * 60;

    /**
     * AI使用次数缓存键前缀
     * 格式：chen_stack:AiUsage:用户ID
     * 存储结构：计数器，记录用户当天的AI调用次数
     * 过期时间：到第二天凌晨自动过期
     */
    public static final String AiUsage = RedisKeyPrefix + "AiUsage:";

    /**
     * AI内容哈希缓存键前缀（防重复提交）
     * 格式：chen_stack:AiContentHash:用户ID:内容Hash
     * 存储结构：标记值，防止短时间内重复提交相同内容
     * 过期时间：5分钟
     */
    public static final String AiContentHash = RedisKeyPrefix + "AiContentHash:";

    /**
     * AI内容哈希缓存过期时间（5分钟，单位：秒）
     */
    public static final long AI_CONTENT_HASH_EXPIRE_TIME = 5 * 60;

    /**
     * 私信未读数缓存键前缀
     * 格式：chen_stack:PrivateMessageUnreadCount:用户ID
     * 存储结构：计数器，记录用户的未读私信数
     */
    public static final String PRIVATE_MESSAGE_UNREAD_COUNT_KEY = RedisKeyPrefix + "PrivateMessageUnreadCount:";

    /**
     * 用户在线状态缓存键前缀
     * 格式：chen_stack:UserOnline:用户ID
     * 存储结构：布尔值，true表示在线，false表示离线
     */
    public static final String USER_ONLINE_STATUS_KEY = RedisKeyPrefix + "UserOnline:";

    // ==================== 推荐系统相关常量 ====================

    /**
     * 用户画像缓存键前缀
     * 格式：chen_stack:UserProfile:用户 ID
     */
    public static final String UserProfile = RedisKeyPrefix + "UserProfile:";

    /**
     * 用户画像缓存过期时间（7 天，单位：秒）
     */
    public static final long USER_PROFILE_EXPIRE_TIME = 7 * 24 * 60 * 60;

    /**
     * 用户行为记录缓存键前缀
     * 格式：chen_stack:UserBehavior:用户 ID
     */
    public static final String UserBehavior = RedisKeyPrefix + "UserBehavior:";

    /**
     * 用户行为记录缓存过期时间（30 天，单位：秒）
     */
    public static final long USER_BEHAVIOR_EXPIRE_TIME = 30 * 24 * 60 * 60;

    /**
     * 文章相似度矩阵缓存键前缀
     * 格式：chen_stack:ArticleSimilarity:文章 ID
     */
    public static final String ArticleSimilarity = RedisKeyPrefix + "ArticleSimilarity:";

    /**
     * 文章相似度矩阵缓存过期时间（1 天，单位：秒）
     */
    public static final long ARTICLE_SIMILARITY_EXPIRE_TIME = 24 * 60 * 60;

    /**
     * 用户推荐结果缓存键前缀
     * 格式：chen_stack:UserRecommend:用户 ID
     */
    public static final String UserRecommend = RedisKeyPrefix + "UserRecommend:";

    /**
     * 用户推荐结果缓存过期时间（30 分钟，单位：秒）
     */
    public static final long USER_RECOMMEND_EXPIRE_TIME = 30 * 60;

    /**
     * 实时热度衰减缓存键前缀
     * 格式：chen_stack:HotScoreUpdate:文章 ID
     */
    public static final String HotScoreUpdate = RedisKeyPrefix + "HotScoreUpdate:";

    /**
     * 标签共现矩阵缓存键前缀
     * 格式：chen_stack:TagCooccurrence:标签
     */
    public static final String TagCooccurrence = RedisKeyPrefix + "TagCooccurrence:";

    /**
     * 管理端首页控制台统计数据缓存键
     * 格式：chen_stack:Dashboard:Statistics
     * 用于缓存首页统计数据，避免频繁查询数据库
     */
    public static final String DashboardStatistics = RedisKeyPrefix + "Dashboard:Statistics";

    /**
     * 管理端首页控制台统计数据缓存过期时间（5 分钟，单位：秒）
     * 统计数据不需要实时，5 分钟过期足够
     */
    public static final long DASHBOARD_STATISTICS_EXPIRE_TIME = 5 * 60;

    /**
     * 权限完整列表缓存键
     * 格式：chen_stack:Permission:All
     */
    public static final String PermissionAll = RedisKeyPrefix + "Permission:All";

    /**
     * 权限完整列表缓存过期时间（10 分钟，单位：秒）
     */
    public static final long PERMISSION_ALL_EXPIRE_TIME = 10 * 60;

    /**
     * 用户详细信息缓存键前缀（包含角色、菜单、权限信息）
     * 格式：chen_stack:UserDetail: 用户 ID
     * 用于缓存用户的角色、菜单、权限信息，避免每次请求都查询数据库
     */
    public static final String UserDetail = RedisKeyPrefix + "UserDetail:";

    /**
     * 用户详细信息缓存过期时间（30 分钟，单位：秒）
     * 用户信息不需要实时刷新，30 分钟过期足够
     */
    public static final long USER_DETAIL_EXPIRE_TIME = 30 * 60;

}
