package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysUserRoleDto;
import com.zcx.chenstack.domain.entity.SysUserRole;
import com.zcx.chenstack.domain.vo.SysRoleVo;
import com.zcx.chenstack.domain.vo.SysUserVo;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
public interface SysUserRoleService extends IService<SysUserRole> {

    void addRole(SysUserRoleDto sysUserRoleDto);

    List<SysRoleVo> getRoles(Integer roleId);

    void addUser(SysUserRoleDto sysUserRoleDto);

    List<SysUserVo> getUsers(Integer roleId);

    void setRegisterRole(Integer userId);

}
