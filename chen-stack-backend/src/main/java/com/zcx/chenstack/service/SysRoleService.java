package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysRoleDto;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.vo.PageVo;
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
public interface SysRoleService extends IService<SysRole> {

    // 查询角色列表
    List<SysRoleVo> listRole();

    // 新增角色
    void add(SysRoleDto sysRoleDto);

    // 更新角色
    void update(SysRoleDto sysRoleDto);

    // 删除角色
    void delete(Integer roleId);

    // 查找角色
    List<SysRoleVo> search(String name);

    // 分页查询角色列表
    PageVo<List<SysRoleVo>> pageRole(Integer pageNum, Integer pageSize);

    // 分页搜索角色
    PageVo<List<SysRoleVo>> searchPage(String name, Integer pageNum, Integer pageSize);

}
