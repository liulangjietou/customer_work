package com.richard.fyoung.customeradmin.config;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.tenant.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * Sa-Token 权限数据源：登录用户的角色/权限点查询。
 *
 * <p>{@code role_code=super_admin} 特判直接放行全部权限点——{@code sys_role_permission}
 * 不为超管冗余插入记录（种子数据 V2 已注明该约定），减少维护成本。</p>
 *
 * <p><b>多租户下的两个要点</b>：</p>
 * <ul>
 *   <li>角色查询一律在<b>用户归属租户</b>下进行，而不是当前视角租户。运营方切到租户 X 的视角后，
 *       自己的角色仍在 {@code __platform__} 里，若按视角租户查会一条都查不到，当场失去全部权限。</li>
 *   <li>超管特判额外要求用户属于平台租户。租户可以自建角色，若只比 {@code role_code}，
 *       任何租户建一个叫 {@code super_admin} 的角色就能拿到含 {@code tenant:*} 在内的全部权限点。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
@Component
public class AdminStpInterfaceImpl implements StpInterface {

    private static final String SUPER_ADMIN_ROLE_CODE = "super_admin";

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysPermissionMapper permissionMapper;

    public AdminStpInterfaceImpl(SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
                                 SysRolePermissionMapper rolePermissionMapper,
                                 SysPermissionMapper permissionMapper) {
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.permissionMapper = permissionMapper;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<SysRole> roles = rolesOf(loginId);
        if (roles.isEmpty()) {
            return List.of();
        }
        boolean isSuperAdmin = TenantSession.isPlatformOperator()
            && roles.stream().anyMatch(r -> SUPER_ADMIN_ROLE_CODE.equals(r.getRoleCode()));
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

    /**
     * 查用户的启用角色。
     *
     * <p>固定在用户归属租户下查询：权限属于"用户是谁"，与"当前在看哪个租户的数据"是两件事。
     * 未登录（如 Sa-Token 在解析阶段回调）时归属租户为空，此时不切上下文，沿用调用方的现状。</p>
     */
    private List<SysRole> rolesOf(Object loginId) {
        String userTenant = TenantSession.currentUserTenant();
        if (userTenant == null) {
            return doRolesOf(loginId);
        }
        return TenantContext.callWith(userTenant, () -> doRolesOf(loginId));
    }

    private List<SysRole> doRolesOf(Object loginId) {
        Long userId = Long.valueOf(loginId.toString());
        List<Long> roleIds = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId))
            .stream().map(SysUserRole::getRoleId).toList();
        if (CollectionUtils.isEmpty(roleIds)) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
            .filter(r -> r.getStatus() != null && r.getStatus() == 1)
            .toList();
    }
}
