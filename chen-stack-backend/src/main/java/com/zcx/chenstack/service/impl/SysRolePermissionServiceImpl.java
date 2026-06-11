package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysRolePermissionDto;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysRolePermission;
import com.zcx.chenstack.domain.vo.SysRoleVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysRoleMapper;
import com.zcx.chenstack.mapper.SysRolePermissionMapper;
import com.zcx.chenstack.service.SysRolePermissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-08-06
 */
@Service
public class SysRolePermissionServiceImpl extends ServiceImpl<SysRolePermissionMapper, SysRolePermission> implements SysRolePermissionService {

    @Resource
    private SysRoleMapper sysRoleMapper;

    @Override
    public void add(SysRolePermissionDto sysRolePermissionDto) {
        throw new BlogException(BlogConstants.GenericPermissionAssignmentDisabled);
    }

    @Override
    public List<SysRoleVo> getRoles(Integer permissionId) {
        // 根据权限ID查询角色列表
        List<SysRolePermission> sysRolePermissions = this.lambdaQuery().eq(SysRolePermission::getPermissionId, permissionId).list();
        if (sysRolePermissions.isEmpty()) {
            // sysRolePermissions为空，说明没有角色与权限关联，返回空列表
            return new ArrayList<>();
        }
        List<Integer> roleIds = sysRolePermissions.stream().map(SysRolePermission::getRoleId).toList();
        // 根据角色ID列表查询角色列表
        List<SysRole> sysRoles = sysRoleMapper.selectBatchIds(roleIds);
        if (sysRoles.isEmpty()) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        List<SysRoleVo> sysRoleVos = BeanUtil.copyToList(sysRoles, SysRoleVo.class);
        return sysRoleVos;
    }

    @Override
    public void addBatch(SysRolePermissionDto sysRolePermissionDto) {
        throw new BlogException(BlogConstants.GenericPermissionAssignmentDisabled);
    }
}
