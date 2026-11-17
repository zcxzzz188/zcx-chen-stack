package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.exception.GlobalException;
import com.zcx.chenstack.knowledge.KnowledgeLibrary;
import com.zcx.chenstack.knowledge.KnowledgePages;
import com.zcx.chenstack.knowledge.KnowledgeSearch;
import com.zcx.chenstack.knowledge.KnowledgeSettings;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeReplyStyleSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(KnowledgeLibraryController.class)
    static class Binding {
        @Bean KnowledgeSettings settings() { return mock(KnowledgeSettings.class); }
        @Bean KnowledgeLibrary library() { return mock(KnowledgeLibrary.class); }
        @Bean KnowledgePages pages() { return mock(KnowledgePages.class); }
        @Bean KnowledgeSearch search() { return mock(KnowledgeSearch.class); }
    }

    @Test
    void readOnlyCannotSaveAndInvalidStyleNeverReachesStorage() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withBean("adminSecurity", AdminSecurity.class, () -> mock(AdminSecurity.class))
                .withPropertyValues("chen.knowledge.simple.enabled=true")
                .run(context -> {
                    AdminSecurity security = context.getBean(AdminSecurity.class);
                    KnowledgeSettings settings = context.getBean(KnowledgeSettings.class);
                    KnowledgeLibraryController controller = context.getBean(KnowledgeLibraryController.class);
                    when(security.hasKnowledgePermission("ai:config:read")).thenReturn(true);
                    controller.settings();
                    assertThrows(AccessDeniedException.class, () -> controller.configureReplyStyle(
                            new KnowledgeLibraryController.ReplyStyleInput("FRIENDLY_NATURAL")));

                    when(security.hasKnowledgePermission("ai:config:manage")).thenReturn(true);
                    var http = MockMvcBuilders.standaloneSetup(controller)
                            .setControllerAdvice(new GlobalException()).build();
                    String endpoint = "/admin/knowledge-library/settings/reply-style";
                    http.perform(put(endpoint).contentType("application/json")
                                    .content("{\"replyStyle\":\"任意提示词\"}"))
                            .andExpect(jsonPath("$.code").value(400));
                    http.perform(put(endpoint).contentType("application/json").content("{}"))
                            .andExpect(jsonPath("$.code").value(400));
                    verify(settings).get();
                    verifyNoMoreInteractions(settings);

                    http.perform(put(endpoint).contentType("application/json")
                                    .content("{\"replyStyle\":\"FRIENDLY_NATURAL\"}"))
                            .andExpect(jsonPath("$.code").value(200));
                    verify(settings).saveReplyStyle(KnowledgeSettings.ReplyStyle.FRIENDLY_NATURAL);
                });
    }

    @Test
    void ordinaryUserCallingManagementApiDirectlyIsDenied() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class)
                .withBean("adminSecurity", AdminSecurity.class)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withPropertyValues("chen.knowledge.simple.enabled=true")
                .run(context -> {
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString(), eq(42))).thenReturn(0);
                    SysUser user = new SysUser();
                    user.setId(42);
                    user.setSysRoles(List.of());
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
                    try {
                        var http = MockMvcBuilders.standaloneSetup(context.getBean(KnowledgeLibraryController.class))
                                .setControllerAdvice(new GlobalException()).build();
                        String base = "/admin/knowledge-library";
                        for (String path : List.of("/documents/list", "/entries/list", "/pages/list", "/settings")) {
                            http.perform(get(base + path).param("page", "1").param("size", "10"))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.code").value(401))
                                    .andExpect(jsonPath("$.msg").value("无权限"))
                                    .andExpect(jsonPath("$.data").doesNotExist());
                        }
                        http.perform(put(base + "/pages/9007199254740000/published").contentType("application/json")
                                        .content("{\"enabled\":false}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(401))
                                .andExpect(jsonPath("$.msg").value("无权限"))
                                .andExpect(jsonPath("$.data").doesNotExist());
                        verify(jdbc, times(2)).queryForObject(anyString(), eq(Integer.class), eq("ai:rag:read"), eq(42));
                        for (String permission : List.of("ai:wiki:read", "ai:config:read", "ai:wiki:publish")) {
                            verify(jdbc).queryForObject(anyString(), eq(Integer.class), eq(permission), eq(42));
                        }
                        verifyNoInteractions(context.getBean(KnowledgeLibrary.class), context.getBean(KnowledgePages.class),
                                context.getBean(KnowledgeSettings.class));
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                });
    }
}
