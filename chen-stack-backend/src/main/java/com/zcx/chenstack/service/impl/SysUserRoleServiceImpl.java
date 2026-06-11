package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysUserRoleDto;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.entity.SysUserRole;
import com.zcx.chenstack.domain.vo.SysRoleVo;
import com.zcx.chenstack.domain.vo.SysUserVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysRoleMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.mapper.SysUserRoleMapper;
import com.zcx.chenstack.service.SysUserRoleService;
import com.zcx.chenstack.utils.MyThreadFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
@Service
public class SysUserRoleServiceImpl extends ServiceImpl<SysUserRoleMapper, SysUserRole> implements SysUserRoleService {

    @Resource
    private SysRoleMapper sysRoleMapper;

    @Resource
    private SysUserMapper sysUserMapper;


    // 给角色分配用户
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUser(SysUserRoleDto sysUserRoleDto) {
        throw new BlogException(BlogConstants.AssignRoleFromUserManagement);
    }

    // 获取拥有当前角色的用户列表
    @Override
    public List<SysRoleVo> getRoles(Integer userId) {
        // 根据用户ID查询角色列表
        List<SysUserRole> sysRoleUsers = this.lambdaQuery().eq(SysUserRole::getUserId, userId).list();
        if (sysRoleUsers.isEmpty()) {
            // sysRoleUsers为空，说明没有角色与用户关联，返回空列表
            return new ArrayList<>();
        }
        List<Integer> roleIds = sysRoleUsers.stream().map(SysUserRole::getRoleId).toList();
        // 根据角色ID列表查询角色列表
        List<SysRole> sysRoles = sysRoleMapper.selectBatchIds(roleIds);
        if (sysRoles.isEmpty()) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        List<SysRoleVo> sysRoleVos = BeanUtil.copyToList(sysRoles, SysRoleVo.class);
        return sysRoleVos;
    }

    // 给用户添加角色
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addRole(SysUserRoleDto sysUserRoleDto) {
        Integer userId = sysUserRoleDto.getUserId();
        SysUser sysUser = sysUserMapper.selectById(userId);
        if (ObjectUtil.isEmpty(sysUser)) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }

        List<Integer> distinctRoleIds = sysUserRoleDto.getRoleIds() == null
                ? List.of()
                : sysUserRoleDto.getRoleIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (distinctRoleIds.size() != 1) {
            throw new BlogException(BlogConstants.UserMustHaveSingleRole);
        }

        Integer selectedRoleId = distinctRoleIds.get(0);
        SysRole selectedRole = sysRoleMapper.selectById(selectedRoleId);
        if (ObjectUtil.isEmpty(selectedRole)) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        if (!isBuiltInRole(selectedRole.getRole())) {
            throw new BlogException(BlogConstants.UserCanOnlyUseBuiltInRole);
        }

        Integer adminRoleId = getAdminRoleId();
        boolean currentUserIsAdmin = isAdminUser(userId, adminRoleId);
        boolean selectedAdminRole = adminRoleId.equals(selectedRoleId);

        if (currentUserIsAdmin && !selectedAdminRole) {
            throw new BlogException(BlogConstants.SuperAdminRoleCannotChangeNormally);
        }
        if (!currentUserIsAdmin && selectedAdminRole) {
            throw new BlogException(BlogConstants.SuperAdminMustUseTransfer);
        }

        long otherAdminCount = this.lambdaQuery()
                .eq(SysUserRole::getRoleId, adminRoleId)
                .ne(SysUserRole::getUserId, userId)
                .count();

        long finalAdminCount = otherAdminCount + (selectedAdminRole ? 1 : 0);
        if (finalAdminCount != 1) {
            throw new BlogException(BlogConstants.SuperAdminMustBeUnique);
        }

        List<SysUserRole> currentUserRoles = this.lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .list();

        if (currentUserRoles.size() == 1
                && Objects.equals(currentUserRoles.get(0).getRoleId(), selectedRoleId)) {
            return;
        }

        if (ObjectUtil.isNotEmpty(currentUserRoles)) {
            boolean removed = this.remove(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
            if (!removed) {
                throw new BlogException(BlogConstants.ExistUserRole);
            }
        }

        SysUserRole sysUserRole = new SysUserRole();
        sysUserRole.setUserId(userId);
        sysUserRole.setRoleId(selectedRoleId);
        boolean exist = this.save(sysUserRole);
        if (!exist) {
            throw new BlogException(BlogConstants.ExistUserRole);
        }
    }

    private Integer getAdminRoleId() {
        SysRole adminRole = sysRoleMapper.selectOne(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRole, "admin"));
        if (ObjectUtil.isEmpty(adminRole)) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        return adminRole.getId();
    }

    private boolean isAdminUser(Integer userId, Integer adminRoleId) {
        if (ObjectUtil.hasEmpty(userId, adminRoleId)) {
            return false;
        }
        return this.lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getRoleId, adminRoleId)
                .count() > 0;
    }

    // 获取当前用户拥有的角色列表
    private boolean isBuiltInRole(String roleCode) {
        return roleCode != null && Set.of("admin", "content_admin", "user").contains(roleCode);
    }

    @Override
    public List<SysUserVo> getUsers(Integer roleId) {
        // 根据角色ID查询用户列表
        List<SysUserRole> sysRoleUsers = this.lambdaQuery().eq(SysUserRole::getRoleId, roleId).list();
        if (sysRoleUsers.isEmpty()) {
            // sysRoleUsers为空，说明没有角色与用户关联，返回空列表
            return new ArrayList<>();
        }
        List<Integer> userIds = sysRoleUsers.stream().map(SysUserRole::getUserId).toList();
        // 根据用户ID列表查询用户列表
        List<SysUser> sysUsers = sysUserMapper.selectBatchIds(userIds);
        if (sysUsers.isEmpty()) {
            throw new BlogException(BlogConstants.NotFoundUser);
        }
        List<SysUserVo> sysUserVos = BeanUtil.copyToList(sysUsers, SysUserVo.class);
        return sysUserVos;
    }

    ExecutorService executorService = new ThreadPoolExecutor(
            2, 4, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(500),
            new MyThreadFactory("sysUserRoleServiceImpl")
    );

    @Override
    public void setRegisterRole(Integer userId) {
        executorService.execute(() -> {
            // 设置注册用户的角色
            SysRole sysRole = sysRoleMapper.selectOne(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getRole, "user"));
            if (ObjectUtil.isEmpty(sysRole)) {
                throw new BlogException(BlogConstants.NotFoundRole);
            }
            SysUserRole sysUserRole = new SysUserRole();
            sysUserRole.setUserId(userId);
            sysUserRole.setRoleId(sysRole.getId());
            boolean exist = this.save(sysUserRole);
            if (!exist) {
                throw new BlogException(BlogConstants.ExistUserRole);
            }
        });
    }
}
