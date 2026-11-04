package com.zcx.chenstack.security;

import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.utils.SecurityUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Component("adminSecurity")
public class AdminSecurity {
    @Autowired(required = false)
    private JdbcTemplate jdbc;

    /** 菜单、角色和操作权限一起回源，角色撤权无需等待登录缓存过期。 */
    public boolean hasKnowledgePermission(String permission) {
        if (permission == null || !permission.startsWith("ai:")) return false;
        if (isAdmin()) return true;
        try {
            Integer actor = SecurityUtils.getUserId();
            if (actor == null || actor <= 0 || jdbc == null) return false;
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_user_role ur
                    JOIN sys_user u ON u.id=ur.user_id AND u.status=0 AND u.is_deleted=0
                    JOIN sys_role r ON r.id=ur.role_id AND r.role='content_admin' AND r.status=0 AND r.is_deleted=0
                    JOIN sys_role_permission rp ON rp.role_id=r.id
                    JOIN sys_permission p ON p.id=rp.permission_id AND p.permission=? AND p.is_deleted=0
                    JOIN sys_role_menu rm ON rm.role_id=r.id AND rm.menu_id=p.menu_id
                    JOIN sys_menu m ON m.id=p.menu_id AND m.status=0 AND m.is_deleted=0
                    JOIN sys_menu parent ON parent.id=m.parent_id AND parent.path='/ai' AND parent.status=0 AND parent.is_deleted=0
                    JOIN sys_role_menu root_menu ON root_menu.role_id=r.id AND root_menu.menu_id=parent.id
                    WHERE ur.user_id=?
                    """, Integer.class, permission, actor);
            return count != null && count > 0;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean isAdmin() {
        try {
            SysUser currentUser = SecurityUtils.getUser();
            if (currentUser == null) {
                return false;
            }

            List<SysRole> sysRoles = currentUser.getSysRoles();
            if (sysRoles == null || sysRoles.isEmpty()) {
                return false;
            }

            return sysRoles.stream()
                    .filter(role -> role != null && role.getRole() != null)
                    .anyMatch(role -> "admin".equals(role.getRole()));
        } catch (Exception ignored) {
            return false;
        }
    }
}
