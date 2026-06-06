package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysRolePermissionDto;
import com.zcx.chenstack.domain.entity.SysRolePermission;
import com.zcx.chenstack.domain.vo.SysRoleVo;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author zcx
 * @since 2025-08-06
 */
public interface SysRolePermissionService extends IService<SysRolePermission> {

    void add(SysRolePermissionDto sysRolePermissionDto);

    List<SysRoleVo> getRoles(Integer permissionId);

    void addBatch(SysRolePermissionDto sysRolePermissionDto);
}
