package com.zcx.chenstack.aspect;

import cn.hutool.core.util.StrUtil;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.exception.RateLimitException;
import com.zcx.chenstack.utils.IpUtils;
import com.zcx.chenstack.utils.RedisUtils;
import com.zcx.chenstack.utils.SecurityUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 限流切面
 * 基于Redis实现分布式限流
 * 支持类级别和方法级别的限流配置，方法级别优先级更高
 *
 * @author zcx
 * @since 2025-10-02
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Value("${chen-stack.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private IpUtils ipUtils;

    /**
     * 环绕通知，拦截带有 @RateLimit 注解的类或方法
     * 方法级别注解优先级高于类级别注解
     */
    @Around("@annotation(com.zcx.chenstack.aspect.RateLimit) || @within(com.zcx.chenstack.aspect.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 优先获取方法级别的注解，如果没有则获取类级别的注解
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);
        if (rateLimit == null) {
            rateLimit = method.getDeclaringClass().getAnnotation(RateLimit.class);
        }

        // 如果既没有方法级别也没有类级别的注解，则直接放行
        if (rateLimit == null) {
            return joinPoint.proceed();
        }

        // 限流开关，关闭时直接放行（用于压力测试）
        if (!rateLimitEnabled) {
            return joinPoint.proceed();
        }

        // 获取用户标识（登录用户ID或IP地址）
        String identifier;
        Integer userId = SecurityUtils.getUserId();
        if (!Objects.equals(userId, 0)) {
            identifier = "user:" + userId;
        } else {
            identifier = "ip:" + ipUtils.getIp();
        }

        // 获取 Controller 类名
        String className = signature.getDeclaringType().getSimpleName();

        // 构建限流缓存键：类名:方法名:用户标识
        String rateLimitKey = RedisConstants.RateLimit + className + ":" + method.getName() + ":" + identifier;

        // 执行限流检查并记录访问次数
        boolean allowed;
        Long currentCount;
        try {
            // 访问次数自增
            currentCount = redisUtils.incr(rateLimitKey, 1);

            // 首次访问时设置过期时间
            if (currentCount == 1) {
                redisUtils.expire(rateLimitKey, rateLimit.period(), TimeUnit.SECONDS);
            } else {
                redisUtils.expire(rateLimitKey, rateLimit.period(), TimeUnit.SECONDS);
            }

            // 检查是否超过限流限制
            allowed = currentCount <= rateLimit.value();
        } catch (Exception e) {
            log.error("限流检查异常，允许访问 - key: {}", rateLimitKey, e);
            allowed = true;
        }

        if (!allowed) {
            // 抛出限流异常
            String message = StrUtil.isNotBlank(rateLimit.message()) ? rateLimit.message(): BlogConstants.RateLimitExceeded;
            log.warn("限流触发 - 用户标识: {}, 类: {}, 方法: {}, 限制: {}次/{}秒",
                    identifier, className, method.getName(), rateLimit.value(), rateLimit.period());
            throw new RateLimitException(message);
        }

        // 通过限流检查，执行目标方法
        return joinPoint.proceed();
    }
}
