package com.zcx.chenstack.config;

import com.zcx.chenstack.controller.AiKnowledgeAdminController;
import com.zcx.chenstack.domain.entity.*;
import com.zcx.chenstack.rag.ProductRagAnswerService;
import com.zcx.chenstack.rag.RagKnowledgeService;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiKnowledgeConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiKnowledgeProperties.class)
    @EnableMethodSecurity
    @Import(AiKnowledgeAdminController.class)
    static class Binding {
        @Bean AdminSecurity adminSecurity() { return new AdminSecurity(); }
    }

    @Test void actualYamlBindsTheEnvironmentVariableAndDefaultsToRag() throws Exception {
        var yaml = new YamlPropertySourceLoader().load("actual-application", new ClassPathResource("application.yaml")).getFirst();
        for (String mode : List.of("DEFAULT", "chat", "rag", "wiki")) {
            new ApplicationContextRunner().withUserConfiguration(Binding.class).withInitializer(context -> {
                context.getEnvironment().getPropertySources().addLast(yaml);
                if (!mode.equals("DEFAULT")) context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("isolated-environment", Map.of("AI_KNOWLEDGE_MODE", mode)));
            }).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(mode.equals("DEFAULT") ? "rag" : mode, context.getBean(AiKnowledgeProperties.class).knowledgeMode());
            });
        }
    }

    @Test void invalidAndUnimplementedModesPreventStartupInsteadOfBecomingRag() {
        for (String mode : List.of("", "RAG", "invalid", "hybrid", "auto")) {
            new ApplicationContextRunner().withUserConfiguration(Binding.class)
                    .withPropertyValues("chen.ai.knowledge-mode=" + mode).run(context -> assertNotNull(context.getStartupFailure(), mode));
        }
    }

    @Test void wikiWithoutPublishedAuthorityPreventsServiceStartup() {
        var error = assertThrows(IllegalStateException.class, () -> new ProductRagAnswerService(mock(RagKnowledgeService.class),
                mock(ChatClient.class), mock(ChatMemory.class), null, new AiKnowledgeProperties("wiki"), null));
        assertTrue(error.getMessage().contains("不能回退"));
    }

    @Test void diagnosticRequiresAnAdministratorAndReturnsOnlyModeInformation() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            var controller = context.getBean(AiKnowledgeAdminController.class);
            try {
                SecurityContextHolder.clearContext();
                assertThrows(org.springframework.security.access.AccessDeniedException.class,
                        () -> controller.mode(new MockHttpServletResponse()));
                SysUser user = new SysUser(); user.setId(7); SysRole role = new SysRole(); role.setRole("user"); user.setSysRoles(List.of(role));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
                assertThrows(org.springframework.security.access.AccessDeniedException.class,
                        () -> controller.mode(new MockHttpServletResponse()));
                role.setRole("admin");
                var response = new MockHttpServletResponse();
                var result = controller.mode(response);
                assertEquals("no-store", response.getHeader("Cache-Control"));
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                assertTrue(json.contains("activeMode"));
                assertTrue(json.contains("rag"));
                assertFalse(json.toLowerCase().contains("key"));
                assertFalse(json.contains("source"));
            } finally { SecurityContextHolder.clearContext(); }
        });
    }
}
