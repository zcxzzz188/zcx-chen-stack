package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zcx.chenstack.domain.dto.SysMenuDto;
import com.zcx.chenstack.domain.entity.SysMenu;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysMenuVo;

import java.util.List;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
public interface SysMenuService extends IService<SysMenu> {

    // 查询用户的菜单
    List<SysMenuVo> listMenu();

    // 查询所有用户的菜单列表
    List<SysMenuVo> listAllMenu();

    // 分页查询所有菜单
    PageVo<List<SysMenuVo>> pageMenu(Integer pageNum, Integer pageSize);

    // 新增菜单
    void add(SysMenuDto sysMenuDto);

    // 修改菜单
    void update(SysMenuDto sysMenuDto);

    // 删除菜单
    void delete(Integer id);

    // 根据菜单名称查找菜单
    List<SysMenuVo> search(String name);

    // 分页根据菜单名称查找菜单
    PageVo<List<SysMenuVo>> searchPage(String name, Integer pageNum, Integer pageSize);
}
