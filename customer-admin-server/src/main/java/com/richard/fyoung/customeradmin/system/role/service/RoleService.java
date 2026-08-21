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
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.ControlPlanePermissions;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final SysUserRoleMapper userRoleMapper;
    private final CrossTenantAuthority crossTenantAuthority;

    public RoleService(SysRoleMapper roleMapper, SysRolePermissionMapper rolePermissionMapper,
                       SysPermissionMapper permissionMapper, SysUserRoleMapper userRoleMapper,
                       CrossTenantAuthority crossTenantAuthority) {
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.crossTenantAuthority = crossTenantAuthority;
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

    @Transactional(rollbackFor = Exception.class)
    public void create(RoleSaveRequest request) {
        if (roleMapper.exists(new LambdaQueryWrapper<SysRole>().eq(SysRole::getRoleCode, request.roleCode()))) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleName(request.roleName());
        role.setRoleCode(request.roleCode());
        role.setRemark(request.remark());
        role.setStatus(request.status() == null ? 1 : request.status());
        role.setControlPlane(SysRole.CONTROL_PLANE_DISABLED);
        role.setDataScope(resolveDataScope(request.dataScope()).name());

        // 坑：sys_role.uk_sys_role_code 是不含 deleted 列的纯数据库唯一约束，delete() 走逻辑删除，
        // 被删过的编码仍占着唯一索引。上面的 exists() 会被自动追加 deleted=0 判定“可用”，但直接
        // insert 会撞唯一键抛 DuplicateKeyException（与 AuthService/KnowledgeBaseService 同款坑）。
        // 先查有没有被软删除过的旧行，有就“复活”它再整体覆盖字段，而不是插新行——若只把异常兜成
        // 友好提示，一个编码被删过一次就永久不能再用，那是功能缺陷而不只是错误信息不友好。
        SysRole softDeleted = roleMapper.selectDeletedByRoleCode(request.roleCode());
        if (softDeleted != null) {
            guardControlPlaneRole(softDeleted, "恢复");
            List<Long> permissionIds = validateGrantablePermissions(softDeleted, request.permissionIds());
            roleMapper.reviveDeleted(softDeleted.getId());
            role.setId(softDeleted.getId());
            role.setControlPlane(softDeleted.getControlPlane());
            roleMapper.updateById(role);
            replacePermissions(softDeleted.getId(), permissionIds);
            log.info("revived soft-deleted role for re-create, roleCode={}, roleId={}",
                request.roleCode(), softDeleted.getId());
            return;
        }
        List<Long> permissionIds = validateGrantablePermissions(role, request.permissionIds());
        // 纯并发竞争（两个请求同时创建同编码角色，都没查到对方尚未提交的行）仍可能撞唯一键：兜成
        // 友好的业务异常，不让 DuplicateKeyException 裸奔到 GlobalExceptionHandler 变成 SYSTEM_ERROR。
        try {
            roleMapper.insert(role);
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "角色编码已存在");
        }
        replacePermissions(role.getId(), permissionIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, RoleSaveRequest request) {
        SysRole role = requireRole(id);
        guardControlPlaneRole(role, "编辑");
        guardSuperAdmin(role, "编辑");
        List<Long> permissionIds = validateGrantablePermissions(role, request.permissionIds());
        role.setRoleName(request.roleName());
        if (request.status() != null) {
            role.setStatus(request.status());
        }
        role.setRemark(request.remark());
        role.setDataScope(resolveDataScope(request.dataScope()).name());
        roleMapper.updateById(role);
        replacePermissions(id, permissionIds);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysRole role = requireRole(id);
        guardControlPlaneRole(role, "删除");
        guardSuperAdmin(role, "删除");
        roleMapper.deleteById(id);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
        // 角色软删除后可能按同一主键复活；必须同步清理用户关系，避免历史用户在复活时无审批恢复权限。
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
    }

    /**
     * 校验并归一化数据范围：只有控制面用户能把角色设成 ALL。
     *
     * <p>租户管理员可以在自己租户里建角色，若不拦这一下，任意租户建一个 ALL 角色就能越出本租户。
     * 前端已按登录方隐藏该选项，但那只是体验——越权判定必须收在服务端。</p>
     */
    private DataScope resolveDataScope(String raw) {
        DataScope scope = DataScope.parse(raw);
        if (scope == DataScope.ALL && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以设置「全部数据」范围");
        }
        return scope;
    }

    private void guardSuperAdmin(SysRole role, String action) {
        if (SystemRoles.SUPER_ADMIN.equals(role.getRoleCode())) {
            throw new BizException(ResultCode.FORBIDDEN, "超级管理员角色不可" + action);
        }
    }

    private void guardControlPlaneRole(SysRole role, String action) {
        if (crossTenantAuthority.isControlPlaneRole(role)
            && !crossTenantAuthority.hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.FORBIDDEN, "只有控制面角色可以" + action + "控制面角色");
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

    /**
     * 普通角色不能被重新授予控制面专属权限；迁移和开通阶段的清理只负责初始状态，
     * 这里才是后续每次角色编辑都生效的领域约束。
     */
    private List<Long> validateGrantablePermissions(SysRole role, List<Long> requestedIds) {
        if (CollectionUtils.isEmpty(requestedIds)) {
            return List.of();
        }
        List<Long> permissionIds = requestedIds.stream().filter(Objects::nonNull).distinct().toList();
        List<SysPermission> permissions = permissionMapper.selectBatchIds(permissionIds);
        if (permissions.size() != permissionIds.size()) {
            throw new BizException(ResultCode.PARAM_INVALID, "包含不存在的权限点");
        }
        boolean containsControlPlanePermission = permissions.stream()
            .anyMatch(permission -> ControlPlanePermissions.isControlPlaneOnly(permission.getPermCode()));
        if (!crossTenantAuthority.isControlPlaneRole(role) && containsControlPlanePermission) {
            throw new BizException(ResultCode.FORBIDDEN, "普通角色不能授予控制面专属权限");
        }
        return permissionIds;
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
        vo.setControlPlane(crossTenantAuthority.isControlPlaneRole(role));
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
