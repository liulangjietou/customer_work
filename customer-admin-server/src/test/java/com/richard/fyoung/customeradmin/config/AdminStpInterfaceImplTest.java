package com.richard.fyoung.customeradmin.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AdminStpInterfaceImpl} 的超管全权限控制面门禁测试。 */
class AdminStpInterfaceImplTest {

    private UserRoleResolver userRoleResolver;
    private SysRolePermissionMapper rolePermissionMapper;
    private SysPermissionMapper permissionMapper;
    private AdminStpInterfaceImpl stpInterface;

    @BeforeEach
    void setUp() {
        userRoleResolver = mock(UserRoleResolver.class);
        rolePermissionMapper = mock(SysRolePermissionMapper.class);
        permissionMapper = mock(SysPermissionMapper.class);
        stpInterface = new AdminStpInterfaceImpl(
            userRoleResolver,
            rolePermissionMapper,
            permissionMapper,
            new CrossTenantAuthority(userRoleResolver));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void getPermissionList_shouldNotTrustSuperAdminRoleCodeWithoutControlPlaneFlag() {
        when(userRoleResolver.enabledRolesOf(7L)).thenReturn(List.of(role(1L, "super_admin", 0)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            assertEquals(List.of(), stpInterface.getPermissionList(7L, "login"));
        }
        verify(permissionMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void getPermissionList_shouldGrantAllOnlyToControlPlaneSuperAdminRole() {
        when(userRoleResolver.enabledRolesOf(7L))
            .thenReturn(List.of(role(1L, "super_admin", SysRole.CONTROL_PLANE_ENABLED)));
        SysPermission permission = new SysPermission();
        permission.setPermCode("tenant:view");
        when(permissionMapper.selectList(null)).thenReturn(List.of(permission));

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            assertEquals(List.of("tenant:view"), stpInterface.getPermissionList(7L, "login"));
        }
    }

    @Test
    void getPermissionList_shouldBindUserTenantForOrdinaryRolePermissionQueries() {
        when(userRoleResolver.enabledRolesOf(7L)).thenReturn(List.of(role(2L, "developer", 0)));
        SysRolePermission relation = new SysRolePermission();
        relation.setRoleId(2L);
        relation.setPermissionId(11L);
        when(rolePermissionMapper.selectList(any())).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return List.of(relation);
        });
        SysPermission permission = new SysPermission();
        permission.setPermCode("workspace");
        when(permissionMapper.selectBatchIds(List.of(11L))).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return List.of(permission);
        });

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn("tenant-a");

            assertEquals(List.of("workspace"), stpInterface.getPermissionList(7L, "login"));
        }
        assertFalse(TenantContext.isPresent(), "鉴权结束后不能泄漏用户归属租户上下文");
    }

    private SysRole role(Long id, String roleCode, int controlPlane) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(roleCode);
        role.setControlPlane(controlPlane);
        role.setStatus(1);
        return role;
    }
}
