package com.zcx.chenstack.controller;

import com.zcx.chenstack.security.AdminSecurity;
import com.zcx.chenstack.service.AiKnowledgeManagementService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiKnowledgeManagementSecurityTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    @Import(AiKnowledgeManagementController.class)
    static class Binding {
        @Bean AdminSecurity adminSecurity() { return mock(AdminSecurity.class); }
        @Bean AiKnowledgeManagementService management() { return mock(AiKnowledgeManagementService.class); }
        @Bean com.zcx.chenstack.rag.NormalAiUsage usage() { return mock(com.zcx.chenstack.rag.NormalAiUsage.class); }
    }

    @Test void readPermissionDoesNotGrantMutationOrTestEndpoints() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            AdminSecurity security = context.getBean(AdminSecurity.class);
            AiKnowledgeManagementService management = context.getBean(AiKnowledgeManagementService.class);
            AiKnowledgeManagementController controller = context.getBean(AiKnowledgeManagementController.class);
            when(security.hasKnowledgePermission("ai:rag:read")).thenReturn(true);
            controller.document("password_reset");
            verify(management).document("password_reset");
            assertThrows(AccessDeniedException.class, () -> controller.revoke("password_reset",
                    new AiKnowledgeManagementController.Revoke("request-1", 1, "a".repeat(64), true)));
            assertThrows(AccessDeniedException.class, () -> controller.test(new AiKnowledgeManagementController.Test("忘记密码", "rag")));
            verifyNoMoreInteractions(management);
        });
    }

    @Test void sourceChangesRequireExplicitConfirmationAndBoundedInput() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var unconfirmed = new AiKnowledgeManagementController.Update("request-1", 1, "a".repeat(64), "密码帮助", "经过核验的正文",
                    "v2", List.of("密码"), false);
            assertFalse(validator.validate(unconfirmed).isEmpty());
            assertFalse(validator.validate(new AiKnowledgeManagementController.Revoke("request-1", 1, "a".repeat(64), false)).isEmpty());
            var valid = new AiKnowledgeManagementController.Update("request-1", 1, "a".repeat(64), "密码帮助", "经过核验的正文",
                    "v2", List.of("密码"), true);
            assertTrue(validator.validate(valid).isEmpty());
            assertFalse(validator.validate(new AiKnowledgeManagementController.Revoke("../unsafe", -1, "bad", true)).isEmpty());
        }
    }

    @Test void knowledgeAuditEndpointUsesTheSameLimitAsThePersistentLedger() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            when(context.getBean(AdminSecurity.class).hasKnowledgePermission("ai:knowledge:test")).thenReturn(true);
            var controller = context.getBean(AiKnowledgeManagementController.class);
            assertDoesNotThrow(() -> controller.knowledgeUsage(50));
            assertThrows(com.zcx.chenstack.wiki.WikiProductException.class, () -> controller.knowledgeUsage(51));
        });
    }

    @Test void unavailableAuditStorageIsReportedAsServiceFailureAfterAuthorization() {
        new ApplicationContextRunner().withUserConfiguration(Binding.class).run(context -> {
            when(context.getBean(AdminSecurity.class).hasKnowledgePermission("ai:knowledge:test")).thenReturn(true);
            when(context.getBean(com.zcx.chenstack.rag.NormalAiUsage.class).recentKnowledgeUses(20))
                    .thenThrow(new SecurityException("NORMAL_AI_USAGE_PERSISTENCE_FAILED_CLOSED"));
            var failure = assertThrows(com.zcx.chenstack.wiki.WikiProductException.class,
                    () -> context.getBean(AiKnowledgeManagementController.class).knowledgeUsage(20));
            assertEquals(503, failure.status());
        });
    }
}
