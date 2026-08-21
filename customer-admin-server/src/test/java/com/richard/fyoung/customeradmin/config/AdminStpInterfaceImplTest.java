package com.richard.fyoung.customeradmin.config;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    @Test
    void getPermissionList_shouldNotTrustSuperAdminRoleCodeWithoutControlPlaneFlag() {
        when(userRoleResolver.enabledRolesOf(7L)).thenReturn(List.of(role(1L, "super_admin", 0)));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        assertEquals(List.of(), stpInterface.getPermissionList(7L, "login"));
        verify(permissionMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void getPermissionList_shouldGrantAllOnlyToControlPlaneSuperAdminRole() {
        when(userRoleResolver.enabledRolesOf(7L))
            .thenReturn(List.of(role(1L, "super_admin", SysRole.CONTROL_PLANE_ENABLED)));
        SysPermission permission = new SysPermission();
        permission.setPermCode("tenant:view");
        when(permissionMapper.selectList(null)).thenReturn(List.of(permission));

        assertEquals(List.of("tenant:view"), stpInterface.getPermissionList(7L, "login"));
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
