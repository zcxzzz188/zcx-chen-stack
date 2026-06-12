package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.SysPermissionDto;
import com.zcx.chenstack.domain.entity.SysMenu;
import com.zcx.chenstack.domain.entity.SysPermission;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.SysPermissionVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.SysMenuMapper;
import com.zcx.chenstack.mapper.SysPermissionMapper;
import com.zcx.chenstack.redis.RedisComponent;
import com.zcx.chenstack.service.SysPermissionService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    private RedisComponent redisComponent;

    @Override
    public List<SysPermissionVo> listPermission() {
        return new ArrayList<>(getFullPermissionList());
    }

    @Override
    public PageVo<List<SysPermissionVo>> pagePermission(Integer pageNum, Integer pageSize) {
        return buildPageResult(getFullPermissionList(), pageNum, pageSize);
    }

    @Override
    public void add(SysPermissionDto sysPermissionDto) {
        throw new BlogException(BlogConstants.PermissionDefinitionReadOnly);
    }

    @Override
    public void update(SysPermissionDto sysPermissionDto) {
        throw new BlogException(BlogConstants.PermissionDefinitionReadOnly);
    }

    @Override
    public void delete(Integer permissionId) {
        throw new BlogException(BlogConstants.PermissionDefinitionReadOnly);
    }

    @Override
    public List<SysPermissionVo> search(SysPermissionDto sysPermissionDto) {
        return filterPermissions(getFullPermissionList(), sysPermissionDto);
    }

    @Override
    public PageVo<List<SysPermissionVo>> searchPage(SysPermissionDto sysPermissionDto) {
        List<SysPermissionVo> filteredPermissions = filterPermissions(getFullPermissionList(), sysPermissionDto);
        Integer pageNum = sysPermissionDto == null ? null : sysPermissionDto.getPageNum();
        Integer pageSize = sysPermissionDto == null ? null : sysPermissionDto.getPageSize();
        return buildPageResult(filteredPermissions, pageNum, pageSize);
    }

    private List<SysPermissionVo> getFullPermissionList() {
        List<SysPermissionVo> cachedPermissions = redisComponent.getPermissionListFromCache();
        if (cachedPermissions != null) {
            return cachedPermissions;
        }

        List<SysPermission> sysPermissions = this.lambdaQuery()
                .orderByAsc(SysPermission::getId)
                .list();
        List<SysPermissionVo> permissionVos = buildPermissionVos(sysPermissions);
        redisComponent.setPermissionListToCache(permissionVos);
        return permissionVos;
    }

    private List<SysPermissionVo> buildPermissionVos(List<SysPermission> sysPermissions) {
        List<SysPermissionVo> sysPermissionVos = BeanUtil.copyToList(sysPermissions, SysPermissionVo.class);
        if (ObjectUtil.isNotEmpty(sysPermissions)) {
            List<Integer> menuIds = sysPermissions.stream()
                    .map(SysPermission::getMenuId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (ObjectUtil.isNotEmpty(menuIds)) {
                Map<Integer, SysMenu> sysMenuMap = sysMenuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                                .in(SysMenu::getId, menuIds))
                        .stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(SysMenu::getId, menu -> menu, (existing, replacement) -> existing));
                sysPermissionVos.forEach(sysPermission -> {
                    SysMenu menu = sysMenuMap.get(sysPermission.getMenuId());
                    if (menu != null) {
                        sysPermission.setMenuName(menu.getName());
                        sysPermission.setIcon(menu.getIcon());
                    }
                });
            }
        }
        return sysPermissionVos;
    }

    private List<SysPermissionVo> filterPermissions(List<SysPermissionVo> permissionVos, SysPermissionDto sysPermissionDto) {
        if (ObjectUtil.isEmpty(permissionVos)) {
            return List.of();
        }

        String description = sysPermissionDto == null ? null : sysPermissionDto.getDescription();
        String permission = sysPermissionDto == null ? null : sysPermissionDto.getPermission();
        Integer menuId = sysPermissionDto == null ? null : sysPermissionDto.getMenuId();

        return permissionVos.stream()
                .filter(permissionVo -> matchesSearchCondition(permissionVo, description, permission, menuId))
                .collect(Collectors.toList());
    }

    private boolean matchesSearchCondition(SysPermissionVo permissionVo, String description, String permission, Integer menuId) {
        if (permissionVo == null) {
            return false;
        }
        if (ObjectUtil.isNotEmpty(description)
                && !containsIgnoreCase(permissionVo.getDescription(), description)) {
            return false;
        }
        if (ObjectUtil.isNotEmpty(permission)
                && !containsIgnoreCase(permissionVo.getPermission(), permission)) {
            return false;
        }
        if (ObjectUtil.isNotEmpty(menuId) && !Objects.equals(permissionVo.getMenuId(), menuId)) {
            return false;
        }
        return true;
    }

    private boolean containsIgnoreCase(String source, String keyword) {
        if (source == null) {
            return false;
        }
        return source.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private PageVo<List<SysPermissionVo>> buildPageResult(List<SysPermissionVo> permissionVos, Integer pageNum, Integer pageSize) {
        List<SysPermissionVo> safePermissions = permissionVos == null ? List.of() : permissionVos;
        int safePageNum = pageNum == null || pageNum <= 0 ? 1 : pageNum;
        int safePageSize = pageSize == null || pageSize <= 0 ? 10 : pageSize;
        long total = safePermissions.size();
        long startLong = Math.max(0L, ((long) safePageNum - 1) * safePageSize);
        int start = (int) Math.min(startLong, safePermissions.size());
        int end = Math.min(start + safePageSize, safePermissions.size());
        if (start >= end) {
            return new PageVo<>(List.of(), total);
        }
        return new PageVo<>(new ArrayList<>(safePermissions.subList(start, end)), total);
    }
}
