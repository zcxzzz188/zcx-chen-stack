package com.zcx.chenstack.service.impl;

import com.zcx.chenstack.domain.constants.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiUsageServiceImplTest {
    @Test void defaultLimitAndQuotaAreOneHundredAndNeverBecomeNegative() {
        var service = new AiUsageServiceImpl();
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        assertEquals(100, service.getDailyLimit());
        assertEquals(100, service.getDailyLimit(7));
        assertEquals(100, service.getRemainingQuota(7));
        when(values.get(RedisConstants.AiUsage + 7)).thenReturn("99", "100", "101");
        assertEquals(1, service.getRemainingQuota(7));
        assertEquals(0, service.getRemainingQuota(7));
        assertEquals(0, service.getRemainingQuota(7));
        assertThrows(IllegalArgumentException.class, () -> service.configureDailyLimit(0));
    }

    @Test void administratorLimitAppliesToNewReservationsWithoutClearingUsedCount() {
        var service = new AiUsageServiceImpl();
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        var runtime = mock(com.zcx.chenstack.service.AiRuntimeConfigurationService.class);
        when(runtime.dailyLimit()).thenReturn(150);
        ReflectionTestUtils.setField(service, "runtimeConfiguration", runtime);

        assertEquals(150, service.getDailyLimit(7));
        when(values.get(RedisConstants.AiUsage + 7)).thenReturn("7");
        assertEquals(143, service.getRemainingQuota(7));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertTrue(service.tryReserveUsage(7));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisConstants.AiUsage + 7)), eq("150"), anyString());

        // 降低到已用量以下时剩余额度为0，但不会清空当天计数或出现负数。
        when(runtime.dailyLimit()).thenReturn(5);
        assertEquals(0, service.getRemainingQuota(7));
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertFalse(service.tryReserveUsage(7));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisConstants.AiUsage + 7)), eq("5"), anyString());
    }

    @Test void reservationUsesAtomicScriptAndBusinessMidnightOnBothSidesOfTheDayBoundary() {
        var service = new AiUsageServiceImpl();
        var redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L, 0L, 1L);
        var zone = ZoneId.of("Asia/Shanghai");
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-11-03T15:59:59Z"), zone));
        assertTrue(service.tryReserveUsage(7));
        assertFalse(service.tryReserveUsage(7));
        verify(redis, times(2)).execute(any(RedisScript.class), eq(List.of(RedisConstants.AiUsage + 7)),
                eq("100"), eq(String.valueOf(Instant.parse("2026-11-03T16:00:00Z").getEpochSecond())));
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-11-03T16:00:00Z"), zone));
        assertTrue(service.tryReserveUsage(7));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisConstants.AiUsage + 7)),
                eq("100"), eq(String.valueOf(Instant.parse("2026-11-04T16:00:00Z").getEpochSecond())));
        assertFalse(service.tryReserveUsage(null));
        assertFalse(service.tryReserveUsage(0));
    }
}
