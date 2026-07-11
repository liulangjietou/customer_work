package com.richard.fyoung.customeradmin.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.dto.RoleVO;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色/权限分配管理。
 *
 * <p>{@code role_code=super_admin} 不可编辑/删除（防止误操作导致系统失去管理入口），
 * 其权限点在读取时合成为全量权限 ID（实际不落 {@code sys_role_permission}，
 * 见 {@link com.richard.fyoung.customeradmin.config.AdminStpInterfaceImpl} 的特判）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class RoleService {

    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    public RoleService(SysRoleMapper roleMapper, SysRolePermissionMapper rolePermissionMapper,
                       SysPermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    public PageResult<RoleVO> page(PageQuery query) {
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.like(SysRole::getRoleName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            wrapper.eq(SysRole::getStatus, query.getStatus());
        }
        wrapper.orderBy(true, "asc".equalsIgnoreCase(query.getSortOrder()), SysRole::getCreateTime);

        IPage<SysRole> page = roleMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        IPage<RoleVO> voPage = page.convert(this::toVoWithoutPermissions);
        fillPermissions(voPage.getRecords());
        return PageResult.of(voPage);
    }

    public RoleVO get(Long id) {
        RoleVO vo = toVoWithoutPermissions(requireRole(id));
        fillPermissions(List.of(vo));
        return vo;
    }

    public void create(RoleSaveRequest request) {
        if (roleMapper.exists(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, request.roleCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleName(request.roleName());
        role.setRoleCode(request.roleCode());
        role.setRemark(request.remark());
        role.setStatus(request.status() == null ? 1 : request.status());
        roleMapper.insert(role);
        replacePermissions(role.getId(), request.permissionIds());
    }

    public void update(Long id, RoleSaveRequest request) {
        SysRole role = requireRole(id);
        guardSuperAdmin(role, "编辑");
        role.setRoleName(request.roleName());
        if (request.status() != null) {
            role.setStatus(request.status());
        }
        role.setRemark(request.remark());
        roleMapper.updateById(role);
        replacePermissions(id, request.permissionIds());
    }

    public void delete(Long id) {
        SysRole role = requireRole(id);
        guardSuperAdmin(role, "删除");
        roleMapper.deleteById(id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
    }

    private void guardSuperAdmin(SysRole role, String action) {
        if (SUPER_ADMIN_ROLE_CODE.equals(role.getRoleCode())) {
            throw new BizException(ResultCode.FORBIDDEN, "超级管理员角色不可" + action);
        }
    }

    private void replacePermissions(Long roleId, List<Long> permissionIds) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, roleId));
        if (CollectionUtils.isEmpty(permissionIds)) {
            return;
        }
        for (Long permissionId : permissionIds) {
            SysRolePermission rp = new SysRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionMapper.insert(rp);
        }
    }

    private void fillPermissions(List<RoleVO> roles) {
        if (roles.isEmpty()) {
            return;
        }
        List<Long> superAdminAllIds = permissionMapper.selectList(null).stream().map(SysPermission::getId).toList();

        List<Long> roleIds = roles.stream().map(RoleVO::getId).toList();
        Map<Long, List<Long>> permissionIdsByRole = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
            .stream()
            .collect(Collectors.groupingBy(SysRolePermission::getRoleId,
                Collectors.mapping(SysRolePermission::getPermissionId, Collectors.toList())));

        for (RoleVO vo : roles) {
            if (SUPER_ADMIN_ROLE_CODE.equals(vo.getRoleCode())) {
                vo.setPermissionIds(superAdminAllIds);
            } else {
                vo.setPermissionIds(permissionIdsByRole.getOrDefault(vo.getId(), List.of()));
            }
        }
    }

    private RoleVO toVoWithoutPermissions(SysRole role) {
        RoleVO vo = new RoleVO();
        vo.setId(role.getId());
        vo.setRoleName(role.getRoleName());
        vo.setRoleCode(role.getRoleCode());
        vo.setRemark(role.getRemark());
        vo.setStatus(role.getStatus());
        vo.setCreateTime(role.getCreateTime());
        vo.setPermissionIds(List.of());
        return vo;
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "角色不存在: " + id);
        }
        return role;
    }
}
