package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysRoleDto;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysRoleMenu;
import com.zcx.chenstack.domain.entity.SysRolePermission;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysRoleVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysRoleMapper;
import com.zcx.chenstack.mapper.SysRoleMenuMapper;
import com.zcx.chenstack.mapper.SysRolePermissionMapper;
import com.zcx.chenstack.service.SysRoleService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author zcx
 * @since 2025-06-29
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    @Resource
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Resource
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Override
    public List<SysRoleVo> listRole() {
        List<SysRole> roleList = this.lambdaQuery().orderByAsc(SysRole::getId).list();
        List<SysRoleVo> sysRoleVos = BeanUtil.copyToList(roleList, SysRoleVo.class);
        return sysRoleVos;
    }

    @Override
    public PageVo<List<SysRoleVo>> pageRole(Integer pageNum, Integer pageSize) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> queryWrapper = new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId);
        List<SysRole> roleList = this.page(page, queryWrapper).getRecords();
        return new PageVo<>(BeanUtil.copyToList(roleList, SysRoleVo.class), page.getTotal());
    }

    @Override
    public void add(SysRoleDto sysRoleDto) {
        SysRole sysRole = BeanUtil.copyProperties(sysRoleDto, SysRole.class);
        this.save(sysRole);
    }

    @Override
    public void update(SysRoleDto sysRoleDto) {
        SysRole sysRole = BeanUtil.copyProperties(sysRoleDto, SysRole.class);
        this.updateById(sysRole);
    }

    @Override
    public void delete(Integer roleId) {
        boolean exist = this.removeById(roleId);
        if (!exist) {
            throw new BlogException(BlogConstants.NotFoundRole);
        }
        // 删除角色_菜单关联表
        sysRoleMenuMapper.delete(new QueryWrapper<SysRoleMenu>().lambda().eq(SysRoleMenu::getRoleId, roleId));
        // 删除角色_权限关联表
        sysRolePermissionMapper.delete(new QueryWrapper<SysRolePermission>().lambda().eq(SysRolePermission::getRoleId, roleId));
    }

    @Override
    public List<SysRoleVo> search(String name) {
        List<SysRole> sysRoles = this.lambdaQuery().like(SysRole::getName, name).orderByAsc(SysRole::getId).list();
        List<SysRoleVo> sysRoleVos = BeanUtil.copyToList(sysRoles, SysRoleVo.class);
        return sysRoleVos;
    }

    @Override
    public PageVo<List<SysRoleVo>> searchPage(String name, Integer pageNum, Integer pageSize) {
        Page<SysRole> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> queryWrapper = new LambdaQueryWrapper<SysRole>()
                .like(SysRole::getName, name)
                .orderByAsc(SysRole::getId);
        List<SysRole> sysRoles = this.page(page, queryWrapper).getRecords();
        return new PageVo<>(BeanUtil.copyToList(sysRoles, SysRoleVo.class), page.getTotal());
    }
}
