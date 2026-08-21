package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.constant.SystemRoles;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 权限数据源：登录用户的角色/权限点查询。
 *
 * <p>{@code role_code=super_admin} 特判直接放行全部权限点——{@code sys_role_permission}
 * 不为超管冗余插入记录（种子数据 V2 已注明该约定），减少维护成本。</p>
 *
 * <p><b>多租户下的两个要点</b>：</p>
 * <ul>
 *   <li>角色查询一律在<b>用户归属租户</b>下进行，而不是当前视角租户。控制面用户切到租户 X 的视角后，
 *       自己的角色仍在原归属租户里，若按视角租户查会一条都查不到，当场失去全部权限。</li>
 *   <li>超管特判额外要求同一个角色显式启用 {@code control_plane}。租户可以自建角色，若只比 {@code role_code}，
 *       任何租户建一个叫 {@code super_admin} 的角色就能拿到含 {@code tenant:*} 在内的全部权限点。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminStpInterfaceImpl implements StpInterface {

    private final UserRoleResolver userRoleResolver;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;
    private final CrossTenantAuthority crossTenantAuthority;

    public AdminStpInterfaceImpl(UserRoleResolver userRoleResolver,
                                 SysRolePermissionMapper rolePermissionMapper,
                                 SysPermissionMapper permissionMapper,
                                 CrossTenantAuthority crossTenantAuthority) {
        this.userRoleResolver = userRoleResolver;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<SysRole> roles = rolesOf(loginId);
        if (roles.isEmpty()) {
            return List.of();
        }
        boolean isSuperAdmin = roles.stream().anyMatch(role ->
            SystemRoles.SUPER_ADMIN.equals(role.getRoleCode())
                && crossTenantAuthority.isControlPlaneRole(role));
        if (isSuperAdmin) {
            return permissionMapper.selectList(null).stream()
                .map(SysPermission::getPermCode)
                .toList();
        }

        List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
        List<Long> permissionIds = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<SysRolePermission>().in(SysRolePermission::getRoleId, roleIds))
            .stream().map(SysRolePermission::getPermissionId).toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectBatchIds(permissionIds).stream()
            .map(SysPermission::getPermCode)
            .toList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return rolesOf(loginId).stream().map(SysRole::getRoleCode).toList();
    }

    /** 委托 {@link UserRoleResolver}：角色集合是鉴权与数据权限共用的事实，不在此另写一份。 */
    private List<SysRole> rolesOf(Object loginId) {
        return userRoleResolver.enabledRolesOf(loginId);
    }
}
