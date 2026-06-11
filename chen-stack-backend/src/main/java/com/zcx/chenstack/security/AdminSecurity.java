package com.zcx.chenstack.security;

import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.utils.SecurityUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("adminSecurity")
public class AdminSecurity {

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
