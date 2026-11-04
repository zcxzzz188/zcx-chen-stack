package com.zcx.chenstack.service.impl;

import com.zcx.chenstack.domain.constants.RedisConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 独立 Docker Redis 验证真实 AOF 回放，不读取生产配置、凭据或数据。 */
class AiUsagePersistenceIT {
    @TempDir Path directory;

    @Test void reservationsSurviveAofReplayWithDefaultUserDisabled() throws Exception {
        String container = "chen-ai-quota-persistence-" + UUID.randomUUID();
        Files.writeString(directory.resolve("users.acl"),
                "user default off -@all resetkeys\nuser quota on >fixture-only ~* +@all\n");
        LettuceConnectionFactory connection = null;
        boolean created = false;
        try {
            docker("run", "-d", "--name", container, "--label", "chen.test=quota-persistence",
                    "--pull", "never", "-p", "127.0.0.1::6379", "--mount",
                    "type=bind,source=" + directory.toAbsolutePath() + ",target=/data",
                    System.getProperty("chen.test.redis.image", "redis:7-alpine"), "redis-server",
                    "--dir", "/data", "--aclfile", "/data/users.acl", "--appendonly", "yes",
                    "--appendfsync", "always", "--save", "");
            created = true;
            int userId = 987654321;
            String key = RedisConstants.AiUsage + userId;
            connection = connect(port(container));
            var strings = new StringRedisTemplate(connection);
            var service = service(strings);
            for (int i = 0; i < 7; i++) assertTrue(service.tryReserveUsage(userId));
            assertEquals(93, service.getRemainingQuota(userId));
            long expiry = Long.parseLong(docker("exec", container, "redis-cli", "--no-auth-warning",
                    "--user", "quota", "-a", "fixture-only", "--raw", "EXPIRETIME", key).trim());

            connection.destroy();
            connection = null;
            docker("restart", container);
            connection = connect(port(container));
            strings = new StringRedisTemplate(connection);
            service = service(strings);
            assertEquals(93, service.getRemainingQuota(userId), "AOF 回放必须保留关机前的额度");
            assertEquals(expiry, Long.parseLong(docker("exec", container, "redis-cli", "--no-auth-warning",
                    "--user", "quota", "-a", "fixture-only", "--raw", "EXPIRETIME", key).trim()));
            for (int i = 0; i < 93; i++) assertTrue(service.tryReserveUsage(userId));
            assertFalse(service.tryReserveUsage(userId));

            connection.destroy();
            connection = null;
            docker("restart", container);
            connection = connect(port(container));
            service = service(new StringRedisTemplate(connection));
            assertEquals(0, service.getRemainingQuota(userId));
            assertFalse(service.tryReserveUsage(userId), "重启不能重新获得第 101 次额度");
            assertTrue(docker("exec", container, "redis-cli", "--raw", "GET", key).contains("NOAUTH"));
        } finally {
            if (connection != null) connection.destroy();
            if (created) docker("rm", "-f", container);
        }
    }

    private static AiUsageServiceImpl service(StringRedisTemplate strings) {
        var service = new AiUsageServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", strings);
        return service;
    }

    private static int port(String container) throws Exception {
        String mapping = docker("port", container, "6379/tcp").trim();
        assertTrue(mapping.startsWith("127.0.0.1:"));
        int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
        assertNotEquals(6379, port);
        return port;
    }

    private static LettuceConnectionFactory connect(int port) throws Exception {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", port);
        configuration.setUsername("quota");
        configuration.setPassword("fixture-only");
        var connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        connection.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (var probe = connection.getConnection()) {
                assertEquals("PONG", probe.ping());
                return connection;
            } catch (RuntimeException e) {
                if (System.nanoTime() >= deadline) {
                    connection.destroy();
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    private static String docker(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker");
        command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "隔离 Docker 操作超时");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
