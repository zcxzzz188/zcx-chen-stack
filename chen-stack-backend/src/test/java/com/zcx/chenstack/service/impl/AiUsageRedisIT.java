package com.zcx.chenstack.service.impl;

import com.zcx.chenstack.domain.constants.RedisConstants;
import com.zcx.chenstack.utils.RedisUtils;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 仅对显式指定的隔离 Redis 运行，不读取项目或生产连接配置。 */
class AiUsageRedisIT {
    @Test void oneHundredAtomicReservationsAreIsolatedByUserAndResetAtNaturalMidnight() throws Exception {
        int userId = ThreadLocalRandom.current().nextInt(100_000_000, 1_000_000_000);
        int port = Integer.parseInt(System.getProperty("chen.test.redis.port"));
        assertTrue(port > 0 && port != 6379);
        var connection = new LettuceConnectionFactory("127.0.0.1", port);
        connection.afterPropertiesSet();
        connection.start();
        try {
            var strings = new StringRedisTemplate(connection);
            var values = new RedisTemplate<String, Object>();
            values.setConnectionFactory(connection);
            values.setKeySerializer(new StringRedisSerializer());
            values.setValueSerializer(new StringRedisSerializer());
            values.afterPropertiesSet();
            var redis = new RedisUtils();
            ReflectionTestUtils.setField(redis, "redisTemplate", values);
            var service = new AiUsageServiceImpl();
            ReflectionTestUtils.setField(service, "redisUtils", redis);
            ReflectionTestUtils.setField(service, "stringRedisTemplate", strings);
            try (var workers = Executors.newFixedThreadPool(12)) {
                var attempts = workers.invokeAll(IntStream.range(0, 130)
                        .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> service.tryReserveUsage(userId)).toList());
                int accepted = 0;
                for (var attempt : attempts) if (attempt.get()) accepted++;
                assertEquals(100, accepted);
            }
            assertEquals("100", strings.opsForValue().get(RedisConstants.AiUsage + userId));
            assertEquals(0, service.getRemainingQuota(userId));
            assertFalse(service.tryReserveUsage(userId));
            assertEquals(100, service.getRemainingQuota((userId + 1)));
            assertTrue(service.tryReserveUsage((userId + 1)));
            long ttl = strings.getExpire(RedisConstants.AiUsage + userId, TimeUnit.SECONDS);
            assertTrue(ttl > 0 && ttl <= 86400);

            // 用隔离实例与测试时区让真实 Redis 在两秒后的自然零点过期，不改机器时钟。
            int offset = 86398 - Instant.now().atOffset(ZoneOffset.UTC).toLocalTime().toSecondOfDay();
            if (offset > 64800) offset -= 86400;
            var nearMidnight = ZoneOffset.ofTotalSeconds(offset);
            ReflectionTestUtils.setField(service, "clock", Clock.system(nearMidnight));
            assertTrue(service.tryReserveUsage((userId + 2)));
            Instant previousDay = Instant.now();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (strings.hasKey(RedisConstants.AiUsage + (userId + 2)) && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(strings.hasKey(RedisConstants.AiUsage + (userId + 2)));
            assertNotEquals(previousDay.atOffset(nearMidnight).toLocalDate(), Instant.now().atOffset(nearMidnight).toLocalDate());
            assertEquals(100, service.getRemainingQuota((userId + 2)));
            assertTrue(service.tryReserveUsage((userId + 2)));
            assertEquals(99, service.getRemainingQuota((userId + 2)));
        } finally {
            connection.destroy();
        }
    }
}
