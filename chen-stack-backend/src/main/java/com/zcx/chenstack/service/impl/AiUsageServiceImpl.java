package com.zcx.chenstack.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.service.AiUsageService;
import com.zcx.chenstack.utils.RedisUtils;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI使用配额管理服务实现类
 *
 * @author zcx
 * @since 2025-10-12
 */
@Service
@Slf4j
public class AiUsageServiceImpl implements AiUsageService {

    private int dailyLimit = 100;
    private Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));

    /** 管理员可视配置优先；缺失或读取失败时回退部署默认值，历史计数保持不变。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zcx.chenstack.service.AiRuntimeConfigurationService runtimeConfiguration;

    @Value("${chen-stack.ai.daily-limit:100}")
    void configureDailyLimit(int dailyLimit) {
        if (dailyLimit < 1) {
            throw new IllegalArgumentException("chen-stack.ai.daily-limit must be positive");
        }
        this.dailyLimit = dailyLimit;
    }

    @Value("${spring.jackson.time-zone:Asia/Shanghai}")
    void configureTimeZone(String timeZone) {
        clock = Clock.system(ZoneId.of(timeZone));
    }
    // 单次写入同时保存计数与期限，避免旧版 Redis 在受限默认 ACL 下丢弃 AOF 事务。
    private static final DefaultRedisScript<Long> RESERVE_DAILY_USAGE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and tonumber(current) >= tonumber(ARGV[1]) then
                redis.call('EXPIREAT', KEYS[1], ARGV[2])
                return 0
            end
            redis.call('SET', KEYS[1], (tonumber(current) or 0) + 1, 'EXAT', ARGV[2])
            return 1
            """, Long.class);

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${chen-stack.ai.quota-redis-host:}")
    private String quotaRedisHost;
    @Value("${chen-stack.ai.quota-redis-port:6379}")
    private int quotaRedisPort;
    @Value("${chen-stack.ai.quota-redis-username:}")
    private String quotaRedisUsername;
    @Value("${chen-stack.ai.quota-redis-password:}")
    private String quotaRedisPassword;
    @Value("${chen-stack.ai.quota-redis-database:0}")
    private int quotaRedisDatabase;
    private LettuceConnectionFactory quotaConnectionFactory;

    @PostConstruct
    void configureQuotaStore() {
        if (quotaRedisHost == null || quotaRedisHost.isBlank()) {
            return;
        }
        // 独立 Wiki 仅共享日额度，不切换其会话和其他业务缓存。
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(quotaRedisHost, quotaRedisPort);
        configuration.setDatabase(quotaRedisDatabase);
        if (quotaRedisUsername != null && !quotaRedisUsername.isBlank()) {
            configuration.setUsername(quotaRedisUsername);
        }
        if (quotaRedisPassword != null && !quotaRedisPassword.isBlank()) {
            configuration.setPassword(quotaRedisPassword);
        }
        quotaConnectionFactory = new LettuceConnectionFactory(configuration);
        quotaConnectionFactory.afterPropertiesSet();
        quotaConnectionFactory.start();
        stringRedisTemplate = new StringRedisTemplate(quotaConnectionFactory);
    }

    @PreDestroy
    void closeQuotaStore() {
        if (quotaConnectionFactory != null) {
            quotaConnectionFactory.destroy();
        }
    }

    @Override
    public boolean tryReserveUsage(Integer userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        String key = RedisConstants.AiUsage + userId;
        Long reserved = stringRedisTemplate.execute(
                RESERVE_DAILY_USAGE,
                List.of(key),
                String.valueOf(getDailyLimit(userId)),
                String.valueOf(nextMidnight().getEpochSecond()));
        return Long.valueOf(1L).equals(reserved);
    }

    /**
     * 检查用户今日AI调用次数是否已达上限
     *
     * @param userId 用户ID
     * @return true-未达上限可继续调用，false-已达上限无法调用
     */
    @Override
    public boolean checkDailyLimit(Integer userId) {
        String key = RedisConstants.AiUsage + userId;
        String countObj = stringRedisTemplate.opsForValue().get(key);
        int dailyLimit = getDailyLimit(userId);

        if (countObj == null) {
            return true;
        }

        Long count = Long.valueOf(countObj);
        boolean allowed = count < dailyLimit;
        if (!allowed) {
            log.warn("用户 [ID: {}] 今日AI调用次数已达上限 ({}/{})", userId, count, dailyLimit);
        }

        return allowed;
    }

    /**
     * 记录用户AI使用次数
     * 首次调用时会设置过期时间到第二天凌晨，实现每日计数重置
     *
     * @param userId 用户ID
     */
    @Override
    public void recordUsage(Integer userId) {
        String key = RedisConstants.AiUsage + userId;
        Long count = stringRedisTemplate.opsForValue().increment(key);

        // 首次调用时设置过期时间为当天剩余时间（到第二天凌晨过期）
        if (count == 1) {
            stringRedisTemplate.expireAt(key, nextMidnight());
        }
    }

    /**
     * 获取用户今日剩余AI调用配额
     *
     * @param userId 用户ID
     * @return 剩余可调用次数（0表示已用完）
     */
    @Override
    public int getRemainingQuota(Integer userId) {
        String key = RedisConstants.AiUsage + userId;
        String countObj = stringRedisTemplate.opsForValue().get(key);
        int dailyLimit = getDailyLimit(userId);

        if (countObj == null) {
            return dailyLimit;
        }

        Long count = Long.valueOf(countObj);
        return Math.max(0, dailyLimit - count.intValue());
    }

    @Override
    public int getDailyLimit() {
        return runtimeConfiguration == null ? dailyLimit : runtimeConfiguration.dailyLimit();
    }

    @Override
    public int getDailyLimit(Integer userId) {
        return getDailyLimit();
    }

    /**
     * 检查用户是否在短时间内重复提交相同内容
     * 通过MD5哈希值判断内容是否相同，防止重复请求
     *
     * @param userId  用户ID
     * @param content 提交的内容
     * @return true-内容重复，false-内容不重复
     */
    @Override
    public boolean isDuplicateContent(Integer userId, String content) {
        // 计算内容的MD5哈希值
        String contentHash = DigestUtil.md5Hex(content);
        String key = RedisConstants.AiContentHash + userId + ":" + contentHash;

        // 检查Redis中是否存在该key
        boolean exists = redisUtils.hasKey(key);

        if (exists) {
            log.warn("用户 [ID: {}] 重复提交相同内容（Hash: {}）", userId, contentHash.substring(0, 8));
        }

        return exists;
    }

    /**
     * 记录用户提交内容的哈希值
     * 用于在一定时间内（5分钟）防止重复提交相同内容
     *
     * @param userId  用户ID
     * @param content 提交的内容
     */
    @Override
    public void recordContentHash(Integer userId, String content) {
        // 计算内容的MD5哈希值
        String contentHash = DigestUtil.md5Hex(content);
        String key = RedisConstants.AiContentHash + userId + ":" + contentHash;

        // 记录hash，5分钟内不允许重复提交相同内容
        redisUtils.set(key, "1", RedisConstants.AI_CONTENT_HASH_EXPIRE_TIME, TimeUnit.SECONDS);
    }

    private java.time.Instant nextMidnight() {
        return clock.instant().atZone(clock.getZone()).toLocalDate().plusDays(1)
                .atStartOfDay(clock.getZone()).toInstant();
    }
}
