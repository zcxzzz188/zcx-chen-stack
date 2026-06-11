package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysRoleMenuAssignDto;
import com.zcx.chenstack.domain.dto.SysRoleMenuDto;
import com.zcx.chenstack.domain.entity.SysMenu;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysRoleMenu;
import com.zcx.chenstack.domain.vo.SysRoleVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysMenuMapper;
import com.zcx.chenstack.mapper.SysRoleMapper;
import com.zcx.chenstack.mapper.SysRoleMenuMapper;
import com.zcx.chenstack.service.SysRoleMenuService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
@Service
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {

    @Resource
    private SysRoleMapper sysRoleMapper;

    @Resource
    private SysMenuMapper sysMenuMapper;

    @Override
    public void add(SysRoleMenuDto sysRoleMenuDto) {
        // 获取角色ID列表和菜单ID
        List<Integer> roleIds = normalizeIds(sysRoleMenuDto.getRoleIds());
        Integer menuId = sysRoleMenuDto.getMenuId();
        SysMenu menu = sysMenuMapper.selectById(menuId);
        if (menu == null) {
            throw new BlogException(BlogConstants.NotFoundMenu);
        }
        List<SysRole> roles = getRolesByIds(roleIds);

        if (isSystemManagementMenu(menu)) {
            Integer adminRoleId = getAdminRoleId();
            if (roleIds.size() != 1 || !roleIds.contains(adminRoleId)) {
                throw new BlogException(BlogConstants.SystemMenusOnlyForAdmin);
            }
        } else if (roles.stream().anyMatch(role -> !isBackendManagementRole(role.getRole()))) {
            throw new BlogException(BlogConstants.BackendMenusOnlyForAdminRoles);
        }

        if (ObjectUtil.isEmpty(roleIds)) {
            // 用户没有选择, 删除所有关联记录
            this.remove(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getMenuId, menuId));
            return;
        }

        // 查询已存在的关联记录
        List<SysRoleMenu> existingRoleMenus = this.lambdaQuery()
                .in(SysRoleMenu::getRoleId, roleIds)
                .eq(SysRoleMenu::getMenuId, menuId)
                .list();

        // 构建已存在的roleId集合
        Set<Integer> existingRoleIds = existingRoleMenus.stream()
                .map(SysRoleMenu::getRoleId)
                .collect(Collectors.toSet());

        // 过滤出需要保存的记录
        List<SysRoleMenu> roleMenuList = roleIds.stream()
                .filter(roleId -> !existingRoleIds.contains(roleId))
                .map(roleId -> {
                    SysRoleMenu sysRoleMenu = new SysRoleMenu();
                    sysRoleMenu.setRoleId(roleId);
                    sysRoleMenu.setMenuId(menuId);
                    return sysRoleMenu;
                })
                .collect(Collectors.toList());

        // 如果没有需要保存的记录
        if (ObjectUtil.isNotEmpty(roleMenuList)) {
            // 批量保存不存在的记录
            boolean exist = this.saveBatch(roleMenuList);
            if (!exist) {
                throw new BlogException(BlogConstants.ExistRoleMenu);
            }
        }

        // 过滤出需要删除的记录
        List<SysRoleMenu> noExistingRoleMenus = this.lambdaQuery()
                .notIn(SysRoleMenu::getRoleId, roleIds)
                .eq(SysRoleMenu::getMenuId, menuId)
                .list();

        // 批量删除不存在的记录
        if (ObjectUtil.isNotEmpty(noExistingRoleMenus)) {
            boolean exist = this.removeByIds(noExistingRoleMenus.stream().map(SysRoleMenu::getId).toList());
            if (!exist) {
                throw new BlogException(BlogConstants.ExistRoleMenu);
            }
        }

    }

    @Override
    public List<SysRoleVo> getRoles(Integer menuId) {
        // 根据菜单ID查询角色列表
        List<SysRoleMenu> sysRoleMenus = this.lambdaQuery().eq(SysRoleMenu::getMenuId, menuId).list();
        if (sysRoleMenus.isEmpty()) {
            // sysRoleMenus为空，说明没有角色与菜单关联，返回空列表
            return new ArrayList<>();
        }
        List<Integer> roleIds = sysRoleMenus.stream().map(SysRoleMenu::getRoleId).toList();
        // 根据角色ID列表查询角色列表
        List<SysRole> sysRoles = sysRoleMapper.selectBatchIds(roleIds);
        if (sysRoles.isEmpty()) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        List<SysRoleVo> sysRoleVos = BeanUtil.copyToList(sysRoles, SysRoleVo.class);
        return sysRoleVos;
    }

    @Override
    public List<Integer> getMenus(Integer roleId) {
        // 根据角色ID查询菜单ID列表
        List<SysRoleMenu> sysRoleMenus = this.lambdaQuery().eq(SysRoleMenu::getRoleId, roleId).list();
        if (sysRoleMenus.isEmpty()) {
            return new ArrayList<>();
        }
        return sysRoleMenus.stream().map(SysRoleMenu::getMenuId).toList();
    }

    @Override
    public void assignMenus(SysRoleMenuAssignDto dto) {
        Integer roleId = dto.getRoleId();
        List<Integer> menuIds = normalizeIds(dto.getMenuIds());
        SysRole role = getRoleById(roleId);
        String roleCode = role.getRole();
        List<Integer> systemMenuIds = getSystemManagementMenuIds();
        Set<Integer> systemMenuIdSet = Set.copyOf(systemMenuIds);

        if (isAdminRole(roleCode)) {
            if (ObjectUtil.isEmpty(menuIds) || !menuIds.containsAll(systemMenuIds)) {
                throw new BlogException(BlogConstants.AdminMustKeepSystemMenus);
            }
        } else if (isContentAdminRole(roleCode)) {
            if (menuIds.stream().anyMatch(systemMenuIdSet::contains)) {
                throw new BlogException(BlogConstants.SystemMenusOnlyForAdmin);
            }
        } else if (isUserRole(roleCode)) {
            if (ObjectUtil.isNotEmpty(menuIds)) {
                throw new BlogException(BlogConstants.UserRoleCannotAssignBackendMenu);
            }
        } else if (ObjectUtil.isNotEmpty(menuIds)) {
            throw new BlogException(BlogConstants.BackendMenusOnlyForAdminRoles);
        }

        if (ObjectUtil.isEmpty(menuIds)) {
            // 用户没有选择, 删除该角色所有关联记录
            this.remove(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, roleId));
            return;
        }

        // 查询已存在的关联记录
        List<SysRoleMenu> existingRoleMenus = this.lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId)
                .in(SysRoleMenu::getMenuId, menuIds)
                .list();

        // 构建已存在的menuId集合
        Set<Integer> existingMenuIds = existingRoleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .collect(Collectors.toSet());

        // 过滤出需要保存的记录
        List<SysRoleMenu> roleMenuList = menuIds.stream()
                .filter(menuId -> !existingMenuIds.contains(menuId))
                .map(menuId -> {
                    SysRoleMenu sysRoleMenu = new SysRoleMenu();
                    sysRoleMenu.setRoleId(roleId);
                    sysRoleMenu.setMenuId(menuId);
                    return sysRoleMenu;
                })
                .collect(Collectors.toList());

        // 如果有需要保存的记录，批量保存
        if (ObjectUtil.isNotEmpty(roleMenuList)) {
            boolean exist = this.saveBatch(roleMenuList);
            if (!exist) {
                throw new BlogException(BlogConstants.ExistRoleMenu);
            }
        }

        // 过滤出需要删除的记录（该角色有但不在新菜单列表中的）
        List<SysRoleMenu> noExistingRoleMenus = this.lambdaQuery()
                .eq(SysRoleMenu::getRoleId, roleId)
                .notIn(SysRoleMenu::getMenuId, menuIds)
                .list();

        // 批量删除不需要的记录
        if (ObjectUtil.isNotEmpty(noExistingRoleMenus)) {
            boolean exist = this.removeByIds(noExistingRoleMenus.stream().map(SysRoleMenu::getId).toList());
            if (!exist) {
                throw new BlogException(BlogConstants.ExistRoleMenu);
            }
        }
    }

    private Integer getAdminRoleId() {
        SysRole adminRole = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRole, "admin"));
        if (adminRole == null) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        return adminRole.getId();
    }

    private SysRole getRoleById(Integer roleId) {
        if (roleId == null) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        SysRole sysRole = sysRoleMapper.selectById(roleId);
        if (sysRole == null) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        return sysRole;
    }

    private List<SysRole> getRolesByIds(List<Integer> roleIds) {
        if (ObjectUtil.isEmpty(roleIds)) {
            return List.of();
        }
        List<SysRole> roles = sysRoleMapper.selectBatchIds(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        return roles;
    }

    private boolean isAdminRole(String roleCode) {
        return Objects.equals(roleCode, "admin");
    }

    private boolean isContentAdminRole(String roleCode) {
        return Objects.equals(roleCode, "content_admin");
    }

    private boolean isUserRole(String roleCode) {
        return Objects.equals(roleCode, "user");
    }

    private boolean isBackendManagementRole(String roleCode) {
        return isAdminRole(roleCode) || isContentAdminRole(roleCode);
    }

    private List<Integer> getSystemManagementMenuIds() {
        return sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .select(SysMenu::getId, SysMenu::getPath))
                .stream()
                .filter(this::isSystemManagementMenu)
                .map(SysMenu::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean isSystemManagementMenu(SysMenu menu) {
        if (menu == null || menu.getPath() == null) {
            return false;
        }
        String path = menu.getPath();
        return "/system".equals(path) || path.startsWith("/system/");
    }

    private List<Integer> normalizeIds(List<Integer> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

}
