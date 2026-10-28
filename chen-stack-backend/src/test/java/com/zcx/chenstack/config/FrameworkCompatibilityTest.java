package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.StatusEnum;
import com.zcx.chenstack.mapper.*;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import com.zcx.chenstack.security.JwtAuthenticationFilter;
import com.zcx.chenstack.security.SecurityConfiguration;
import com.zcx.chenstack.security.SysUserDetailsService;
import com.zcx.chenstack.utils.IpUtils;
import com.zcx.chenstack.utils.JwtUtils;
import com.zcx.chenstack.utils.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FrameworkCompatibilityTest {
    @Test void redisAndRabbitKeepTheExistingJsonWireFormat() {
        var redis = new com.zcx.chenstack.redis.RedisConfig().redisTemplate(
                mock(org.springframework.data.redis.connection.RedisConnectionFactory.class));
        @SuppressWarnings("unchecked")
        var serializer = (org.springframework.data.redis.serializer.RedisSerializer<Object>) redis.getValueSerializer();
        byte[] expected = "{\"value\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(expected, serializer.serialize(Map.of("value", 7)));
        assertEquals(Map.of("value", 7), serializer.deserialize(expected));
        var converter = new com.zcx.chenstack.rabbitmq.RabbitMQConfig().messageConverter();
        var message = converter.toMessage(Map.of("value", 7), new org.springframework.amqp.core.MessageProperties());
        assertArrayEquals(expected, message.getBody());
        assertEquals(Map.of("value", 7), converter.fromMessage(message));
    }

    private String[] jsonProperties() throws Exception {
        var source = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        assertEquals("jackson2", source.getProperty("spring.http.converters.preferred-json-mapper"));
        return new String[]{"spring.http.converters.preferred-json-mapper=jackson2",
                "spring.jackson2.date-format=" + source.getProperty("spring.jackson2.date-format"),
                "spring.jackson2.time-zone=" + source.getProperty("spring.jackson2.time-zone")};
    }

    @Test void canonicalBytesAndFingerprintsRemainIdenticalToBoot34Jackson218() throws Exception {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
                .withPropertyValues(jsonProperties()).run(context -> {
                    ObjectMapper json = context.getBean(ObjectMapper.class);
                    var article = Arrays.asList("jsoup-1.18.3-wiki-v1", "ARTICLE", "1", 1, "合成技术资料", "隔离样本",
                            "<p>知识正文</p><script>unsafe()</script>", "Java", 0, null, "知识正文");
                    var help = new KnowledgeDocument("help-1", "合成帮助1", "仅用于隔离测试的公开帮助正文1", "v1",
                            "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("合成"));
                    // 基准由旧 Boot 3.4.0 / Jackson 2.18.1 的真实自动配置生成。
                    assertEquals("5771761c99f3689bbfdef31a15df1392082077e5915b4db501ab5bed7ee4a093",
                            KnowledgeCatalog.hash(json.writeValueAsString(article)));
                    assertEquals("2b20d0655906127ada4d39afa695ad6e8456af0d9f67b3f5799b0004932a5d1c",
                            KnowledgeCatalog.hash(json.writeValueAsString(List.of("help-text-v1", help))));
                    assertEquals("\"1970-01-01 08:00:00\"", json.writeValueAsString(new Date(0)));
                    assertEquals(help, json.readValue(json.writeValueAsString(help), KnowledgeDocument.class));
                });
    }

    @Test void realSecurityChainPreservesNumericPublicRoutesProfileWritesJwtAndMethodPermissions() throws Exception {
        JwtUtils jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "secret", "framework-upgrade-test-only-secret");
        SysUserMapper users = mock(SysUserMapper.class);
        SysUserDetailsService details = mock(SysUserDetailsService.class);
        SysUser user = new SysUser();
        user.setId(7); user.setUsername("fixture"); user.setStatus(StatusEnum.NORMAL.getStatus());
        user.setSysPermissions(List.of());
        when(users.selectById(7)).thenReturn(user);
        when(details.handleLogin(user)).thenReturn(new LoginUser(user));
        ChenStackConfig config = new ChenStackConfig(); config.setAllowOrigins(List.of("http://localhost"));
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withPropertyValues(jsonProperties())
                .withPropertyValues("chen-stack.jwt=framework-upgrade-test-only-secret")
                .withUserConfiguration(WebSecurity.class, Probe.class, SecurityConfiguration.class, JwtAuthenticationFilter.class)
                .withBean(ChenStackConfig.class, () -> config)
                .withBean(JwtUtils.class, () -> jwt)
                .withBean(SysUserMapper.class, () -> users)
                .withBean(SysUserDetailsService.class, () -> details)
                .withBean(SysMenuMapper.class, () -> mock(SysMenuMapper.class))
                .withBean(SysRoleMapper.class, () -> mock(SysRoleMapper.class))
                .withBean(SysUserRoleMapper.class, () -> mock(SysUserRoleMapper.class))
                .withBean(SysRoleMenuMapper.class, () -> mock(SysRoleMenuMapper.class))
                .withBean(SysRolePermissionMapper.class, () -> mock(SysRolePermissionMapper.class))
                .withBean(SysPermissionMapper.class, () -> mock(SysPermissionMapper.class))
                .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
                .withBean(IpUtils.class, () -> mock(IpUtils.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var mvc = MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).apply(springSecurity()).build();
                    mvc.perform(get("/article/get/7")).andExpect(status().isOk());
                    mvc.perform(get("/article/get/not-a-number")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/user/info")).andExpect(status().isOk())
                            .andExpect(content().string("{\"when\":\"1970-01-01 08:00:00\"}"));
                    mvc.perform(put("/user/info")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/ai/customer-service")).andExpect(status().isUnauthorized());
                    mvc.perform(get("/ai/customer-service").header("Authorization", "Bearer malformed"))
                            .andExpect(status().isUnauthorized());
                    String token = "Bearer " + jwt.createToken(7);
                    mvc.perform(get("/ai/customer-service").header("Authorization", token))
                            .andExpect(status().isOk()).andExpect(content().string("7"))
                            .andExpect(header().exists("X-Refresh-Token"));
                    mvc.perform(get("/framework/admin").header("Authorization", token)).andExpect(status().isForbidden());
                    user.setStatus(StatusEnum.DISABLE.getStatus());
                    mvc.perform(get("/ai/customer-service").header("Authorization", token)).andExpect(status().isUnauthorized());
                    when(users.selectById(7)).thenReturn(null);
                    mvc.perform(get("/ai/customer-service").header("Authorization", token)).andExpect(status().isUnauthorized());
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurity { }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class Probe {
        @GetMapping("/article/get/{id}") String article() { return "ok"; }
        @GetMapping("/user/info") Map<String, Date> profile() { return Map.of("when", new Date(0)); }
        @PutMapping("/user/info") String update() { return "ok"; }
        @GetMapping("/ai/customer-service") Integer chat() { return SecurityUtils.getUserId(); }
        @PreAuthorize("hasAuthority('framework:admin')")
        @GetMapping("/framework/admin") String admin() { return "ok"; }
    }
}
