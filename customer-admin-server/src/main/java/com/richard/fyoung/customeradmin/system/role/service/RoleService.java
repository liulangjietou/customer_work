package com.richard.fyoung.customeradmin.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.constant.SystemRoles;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.dto.RoleVO;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

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
        role.setDataScope(resolveDataScope(request.dataScope()).name());

        // 坑：sys_role.uk_sys_role_code 是不含 deleted 列的纯数据库唯一约束，delete() 走逻辑删除，
        // 被删过的编码仍占着唯一索引。上面的 exists() 会被自动追加 deleted=0 判定“可用”，但直接
        // insert 会撞唯一键抛 DuplicateKeyException（与 AuthService/KnowledgeBaseService 同款坑）。
        // 先查有没有被软删除过的旧行，有就“复活”它再整体覆盖字段，而不是插新行——若只把异常兜成
        // 友好提示，一个编码被删过一次就永久不能再用，那是功能缺陷而不只是错误信息不友好。
        SysRole softDeleted = roleMapper.selectDeletedByRoleCode(request.roleCode());
        if (softDeleted != null) {
            roleMapper.reviveDeleted(softDeleted.getId());
            role.setId(softDeleted.getId());
            roleMapper.updateById(role);
            replacePermissions(softDeleted.getId(), request.permissionIds());
            log.info("revived soft-deleted role for re-create, roleCode={}, roleId={}",
                request.roleCode(), softDeleted.getId());
            return;
        }
        // 纯并发竞争（两个请求同时创建同编码角色，都没查到对方尚未提交的行）仍可能撞唯一键：兜成
        // 友好的业务异常，不让 DuplicateKeyException 裸奔到 GlobalExceptionHandler 变成 SYSTEM_ERROR。
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "角色编码已存在");
        }
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
        role.setDataScope(resolveDataScope(request.dataScope()).name());
        roleMapper.updateById(role);
        replacePermissions(id, request.permissionIds());
    }

    public void delete(Long id) {
        SysRole role = requireRole(id);
        guardSuperAdmin(role, "删除");
        roleMapper.deleteById(id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
    }

    /**
     * 校验并归一化数据范围：只有平台运营方能把角色设成 ALL。
     *
     * <p>租户管理员可以在自己租户里建角色，若不拦这一下，任意租户建一个 ALL 角色就能越出本租户。
     * 前端已按登录方隐藏该选项，但那只是体验——越权判定必须收在服务端。</p>
     */
    private DataScope resolveDataScope(String raw) {
        DataScope scope = DataScope.parse(raw);
        if (scope == DataScope.ALL && !TenantSession.isPlatformOperator()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有平台运营方可以设置「全部数据」范围");
        }
        return scope;
    }

    private void guardSuperAdmin(SysRole role, String action) {
        if (SystemRoles.SUPER_ADMIN.equals(role.getRoleCode())) {
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
            if (SystemRoles.SUPER_ADMIN.equals(vo.getRoleCode())) {
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
        vo.setDataScope(DataScope.parse(role.getDataScope()).name());
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
