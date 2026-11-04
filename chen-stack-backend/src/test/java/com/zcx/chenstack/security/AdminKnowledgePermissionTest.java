package com.zcx.chenstack.security;

import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminKnowledgePermissionTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private static void login(String role) {
        SysRole assigned = new SysRole(); assigned.setRole(role);
        SysUser user = new SysUser(); user.setId(42); user.setSysRoles(List.of(assigned));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
    }

    @Test void contentManagerMustHaveCurrentMenuAndOperationPermission() {
        login("content_admin");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminSecurity security = new AdminSecurity(); ReflectionTestUtils.setField(security, "jdbc", jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("ai:wiki:publish"), eq(42))).thenReturn(1, 0);
        assertTrue(security.hasKnowledgePermission("ai:wiki:publish"));
        assertFalse(security.hasKnowledgePermission("ai:wiki:publish"));
        verify(jdbc, times(2)).queryForObject(argThat(sql -> sql.contains("sys_role_permission") && sql.contains("sys_role_menu")
                && sql.contains("parent.status=0") && sql.contains("r.role='content_admin'")), eq(Integer.class), eq("ai:wiki:publish"), eq(42));
    }

    @Test void missingIdentityOrUnavailableAuthorityFailsClosed() {
        AdminSecurity security = new AdminSecurity();
        assertFalse(security.hasKnowledgePermission("ai:rag:manage"));
        login("content_admin");
        assertFalse(security.hasKnowledgePermission("ai:rag:manage"));
        assertFalse(security.hasKnowledgePermission("system:user:delete"));
    }

    @Test void existingSuperAdministratorBehaviorIsPreserved() {
        login("admin");
        assertTrue(new AdminSecurity().hasKnowledgePermission("ai:wiki:publish"));
    }
}
