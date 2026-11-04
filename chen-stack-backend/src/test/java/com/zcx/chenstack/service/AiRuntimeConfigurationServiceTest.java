package com.zcx.chenstack.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRuntimeConfigurationServiceTest {
    @Test void auditLabelUsesTheActualScopedModeKey() {
        assertEquals("小辰工作模式", AiRuntimeConfigurationService.auditLabel(
                "user-assistant.knowledge-mode", "user-assistant.knowledge-mode"));
        assertEquals("小辰工作模式", AiRuntimeConfigurationService.auditLabel(
                "wiki-portal.knowledge-mode", "wiki-portal.knowledge-mode"));
        assertEquals("每日使用额度", AiRuntimeConfigurationService.auditLabel(
                "quota.daily-limit", "user-assistant.knowledge-mode"));
        // 旧实现的错误键名不能再被当作模式事件。
        assertEquals("每日使用额度", AiRuntimeConfigurationService.auditLabel(
                "assistant.knowledge-mode", "user-assistant.knowledge-mode"));
    }
}
