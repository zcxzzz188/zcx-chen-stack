package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysRoleMenuAssignDto;
import com.zcx.chenstack.domain.dto.SysRoleMenuDto;
import com.zcx.chenstack.domain.entity.SysRoleMenu;
import com.zcx.chenstack.domain.vo.SysRoleVo;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
public interface SysRoleMenuService extends IService<SysRoleMenu> {

    void add(SysRoleMenuDto sysRoleMenuDto);

    List<SysRoleVo> getRoles(Integer menuId);

    /**
     * 根据角色ID获取菜单ID列表
     *
     * @param roleId 角色ID
     * @return 菜单ID列表
     */
    List<Integer> getMenus(Integer roleId);

    /**
     * 给角色分配菜单权限
     *
     * @param dto 角色菜单分配信息
     */
    void assignMenus(SysRoleMenuAssignDto dto);
}
