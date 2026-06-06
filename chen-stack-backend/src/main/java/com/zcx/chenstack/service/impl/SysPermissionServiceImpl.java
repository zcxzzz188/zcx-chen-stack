package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysPermissionDto;
import com.zcx.chenstack.domain.entity.SysMenu;
import com.zcx.chenstack.domain.entity.SysPermission;
import com.zcx.chenstack.domain.entity.SysRolePermission;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysPermissionVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysMenuMapper;
import com.zcx.chenstack.mapper.SysPermissionMapper;
import com.zcx.chenstack.mapper.SysRolePermissionMapper;
import com.zcx.chenstack.service.SysPermissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

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
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission> implements SysPermissionService {

    @Resource
    private SysMenuMapper sysMenuMapper;

    @Resource
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Override
    public List<SysPermissionVo> listPermission() {
        List<SysPermission> sysPermissions = this.lambdaQuery().orderByAsc(SysPermission::getId).list();
        return buildPermissionVos(sysPermissions);
    }

    @Override
    public PageVo<List<SysPermissionVo>> pagePermission(Integer pageNum, Integer pageSize) {
        Page<SysPermission> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysPermission> queryWrapper = new LambdaQueryWrapper<SysPermission>()
                .orderByAsc(SysPermission::getId);
        List<SysPermission> sysPermissions = this.page(page, queryWrapper).getRecords();
        return new PageVo<>(buildPermissionVos(sysPermissions), page.getTotal());
    }

    @Override
    public void add(SysPermissionDto sysPermissionDto) {
        SysPermission sysPermission = BeanUtil.copyProperties(sysPermissionDto, SysPermission.class);
        this.save(sysPermission);
    }

    @Override
    public void update(SysPermissionDto sysPermissionDto) {
        SysPermission sysPermission = BeanUtil.copyProperties(sysPermissionDto, SysPermission.class);
        this.updateById(sysPermission);
    }

    @Override
    public void delete(Integer permissionId) {
        boolean exist = this.removeById(permissionId);
        if (!exist) {
            throw new BlogException(BlogConstants.NotFoundPermission);
        }
        // 删除角色_权限关联表
        sysRolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getPermissionId, permissionId));
    }

    @Override
    public List<SysPermissionVo> search(SysPermissionDto sysPermissionDto) {
        LambdaQueryWrapper<SysPermission> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper
                .like(ObjectUtil.isNotEmpty(sysPermissionDto.getDescription()), SysPermission::getDescription, sysPermissionDto.getDescription())
                .like(ObjectUtil.isNotEmpty(sysPermissionDto.getPermission()), SysPermission::getPermission, sysPermissionDto.getPermission())
                .eq(ObjectUtil.isNotEmpty(sysPermissionDto.getMenuId()), SysPermission::getMenuId, sysPermissionDto.getMenuId())
                .orderByAsc(SysPermission::getId);

        List<SysPermission> sysPermissions = this.list(lambdaQueryWrapper);
        return buildPermissionVos(sysPermissions);
    }

    @Override
    public PageVo<List<SysPermissionVo>> searchPage(SysPermissionDto sysPermissionDto) {
        Page<SysPermission> page = new Page<>(sysPermissionDto.getPageNum(), sysPermissionDto.getPageSize());
        LambdaQueryWrapper<SysPermission> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper
                .like(ObjectUtil.isNotEmpty(sysPermissionDto.getDescription()), SysPermission::getDescription, sysPermissionDto.getDescription())
                .like(ObjectUtil.isNotEmpty(sysPermissionDto.getPermission()), SysPermission::getPermission, sysPermissionDto.getPermission())
                .eq(ObjectUtil.isNotEmpty(sysPermissionDto.getMenuId()), SysPermission::getMenuId, sysPermissionDto.getMenuId())
                .orderByAsc(SysPermission::getId);
        List<SysPermission> sysPermissions = this.page(page, lambdaQueryWrapper).getRecords();
        return new PageVo<>(buildPermissionVos(sysPermissions), page.getTotal());
    }

    private List<SysPermissionVo> buildPermissionVos(List<SysPermission> sysPermissions) {
        List<SysPermissionVo> sysPermissionVos = BeanUtil.copyToList(sysPermissions, SysPermissionVo.class);
        if (ObjectUtil.isNotEmpty(sysPermissions)) {
            List<SysMenu> sysMenus = sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                    .in(SysMenu::getId, sysPermissions.stream().map(SysPermission::getMenuId).toList()));
            sysPermissionVos.forEach(sysPermission -> {
                sysMenus.stream()
                        .filter(sysMenu -> sysMenu.getId().equals(sysPermission.getMenuId()))
                        .findFirst()
                        .ifPresent(menu -> {
                            sysPermission.setMenuName(menu.getName());
                            sysPermission.setIcon(menu.getIcon());
                        });
            });
        }
        return sysPermissionVos;
    }
}
