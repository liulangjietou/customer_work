package com.richard.fyoung.customeradmin.system.role.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.dto.RoleVO;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RoleService} 的数据范围相关单测：落库归一化与"ALL 仅控制面角色可授予"的服务端校验。
 * @author owlzhangfq@gmail.com
 */
class RoleServiceDataScopeTest {

    private SysRoleMapper roleMapper;
    private SysPermissionMapper permissionMapper;
    private SysRolePermissionMapper rolePermissionMapper;
    private SysUserRoleMapper userRoleMapper;
    private CrossTenantAuthority crossTenantAuthority;
    private RoleService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        roleMapper = mock(SysRoleMapper.class);
        permissionMapper = mock(SysPermissionMapper.class);
        rolePermissionMapper = mock(SysRolePermissionMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        service = new RoleService(roleMapper, rolePermissionMapper, permissionMapper, userRoleMapper,
            crossTenantAuthority);
        when(roleMapper.exists(any())).thenReturn(false);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 不传范围时按最小权限落 SELF，而不是留空由 SQL 默认值兜底——留空会让"是否设过"变得不可知。 */
    @Test
    void create_shouldDefaultToSelfWhenScopeAbsent() {
        SysRole saved = createWith(null, false);
        assertEquals(DataScope.SELF.name(), saved.getDataScope());
    }

    @Test
    void create_shouldNormalizeUnknownScopeToSelf() {
        SysRole saved = createWith("EVERYTHING", false);
        assertEquals(DataScope.SELF.name(), saved.getDataScope());
    }

    @Test
    void create_shouldAcceptTenantScopeWithoutControlPlaneRole() {
        SysRole saved = createWith("TENANT", false);
        assertEquals(DataScope.TENANT.name(), saved.getDataScope());
    }

    /**
     * 租户管理员能建角色，若让他把角色设成 ALL 就等于自己给自己开跨租户的口子。
     * 前端已隐藏该选项，但那只是体验——越权判定必须收在服务端。
     */
    @Test
    void create_shouldRejectAllScopeWithoutControlPlaneRole() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException ex = assertThrows(BizException.class,
            () -> service.create(request("ALL")));

        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
    }

    @Test
    void create_shouldAllowAllScopeForControlPlaneRole() {
        SysRole saved = createWith("ALL", true);
        assertEquals(DataScope.ALL.name(), saved.getDataScope());
    }

    @Test
    void update_shouldRejectControlPlaneRoleFromOrdinaryUser() {
        SysRole operator = controlPlaneRole(3L);
        when(roleMapper.selectById(3L)).thenReturn(operator);
        when(crossTenantAuthority.isControlPlaneRole(operator)).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> service.update(3L, request("TENANT")));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(roleMapper, never()).updateById(any(SysRole.class));
    }

    @Test
    void create_shouldRejectRevivingDeletedControlPlaneRoleFromOrdinaryUser() {
        SysRole operator = controlPlaneRole(3L);
        when(roleMapper.selectDeletedByRoleCode("tenant-a", "operator")).thenReturn(operator);
        when(crossTenantAuthority.isControlPlaneRole(operator)).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> service.create(request("operator", "TENANT")));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(roleMapper, never()).reviveDeleted(3L, "tenant-a");
    }

    @Test
    void delete_shouldRejectControlPlaneRoleFromOrdinaryUser() {
        SysRole operator = controlPlaneRole(3L);
        when(roleMapper.selectById(3L)).thenReturn(operator);
        when(crossTenantAuthority.isControlPlaneRole(operator)).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> service.delete(3L));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(roleMapper, never()).deleteById(3L);
    }

    @Test
    void delete_shouldRemoveUserAssignmentsToPreventPermissionRevival() {
        SysRole role = new SysRole();
        role.setId(5L);
        role.setRoleCode("auditor");
        when(roleMapper.selectById(5L)).thenReturn(role);

        service.delete(5L);

        verify(roleMapper).deleteById(5L);
        verify(rolePermissionMapper).delete(any());
        verify(userRoleMapper).delete(any());
    }

    @Test
    void update_shouldNotTreatTenantRoleCodeAsControlPlaneSuperAdmin() {
        SysRole tenantRole = new SysRole();
        tenantRole.setId(8L);
        tenantRole.setRoleCode("super_admin");
        tenantRole.setControlPlane(SysRole.CONTROL_PLANE_DISABLED);
        when(roleMapper.selectById(8L)).thenReturn(tenantRole);
        when(crossTenantAuthority.isControlPlaneRole(tenantRole)).thenReturn(false);

        service.update(8L, request("tenant_super_admin", "TENANT"));

        verify(roleMapper).updateById(tenantRole);
    }

    @Test
    void get_shouldNotSynthesizeAllPermissionsForTenantRoleNamedSuperAdmin() {
        SysRole tenantRole = new SysRole();
        tenantRole.setId(8L);
        tenantRole.setRoleCode("super_admin");
        tenantRole.setControlPlane(SysRole.CONTROL_PLANE_DISABLED);
        SysPermission allPermission = new SysPermission();
        allPermission.setId(1L);
        SysRolePermission assigned = new SysRolePermission();
        assigned.setRoleId(8L);
        assigned.setPermissionId(2L);
        when(roleMapper.selectById(8L)).thenReturn(tenantRole);
        when(crossTenantAuthority.isControlPlaneRole(tenantRole)).thenReturn(false);
        when(permissionMapper.selectList(null)).thenReturn(List.of(allPermission));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(assigned));

        RoleVO role = service.get(8L);

        assertFalse(role.getControlPlane());
        assertEquals(List.of(2L), role.getPermissionIds());
    }

    @Test
    void delete_shouldStillProtectControlPlaneSuperAdmin() {
        SysRole superAdmin = new SysRole();
        superAdmin.setId(1L);
        superAdmin.setRoleCode("super_admin");
        superAdmin.setControlPlane(SysRole.CONTROL_PLANE_ENABLED);
        when(roleMapper.selectById(1L)).thenReturn(superAdmin);
        when(crossTenantAuthority.isControlPlaneRole(superAdmin)).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);

        BizException exception = assertThrows(BizException.class, () -> service.delete(1L));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(roleMapper, never()).deleteById(1L);
    }

    @Test
    void create_shouldRejectControlPlanePermissionForOrdinaryRole() {
        SysPermission permission = new SysPermission();
        permission.setId(229L);
        permission.setPermCode("config-version:rollback");
        when(permissionMapper.selectBatchIds(List.of(229L))).thenReturn(List.of(permission));

        BizException exception = assertThrows(BizException.class,
            () -> service.create(new RoleSaveRequest(
                "普通角色", "ordinary", null, 1, "TENANT", List.of(229L))));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(roleMapper, never()).insert(any(SysRole.class));
    }

    @Test
    void update_shouldAllowControlPlaneUserToKeepControlPlanePermissionOnOperator() {
        SysRole operator = controlPlaneRole(3L);
        SysPermission permission = new SysPermission();
        permission.setId(229L);
        permission.setPermCode("config-version:rollback");
        when(roleMapper.selectById(3L)).thenReturn(operator);
        when(crossTenantAuthority.isControlPlaneRole(operator)).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(permissionMapper.selectBatchIds(List.of(229L))).thenReturn(List.of(permission));

        service.update(3L, new RoleSaveRequest(
            "运营管理员", "operator", null, 1, "ALL", List.of(229L)));

        ArgumentCaptor<SysRolePermission> relation = ArgumentCaptor.forClass(SysRolePermission.class);
        verify(rolePermissionMapper).insert(relation.capture());
        assertEquals(3L, relation.getValue().getRoleId());
        assertEquals(229L, relation.getValue().getPermissionId());
    }

    @Test
    void get_shouldExposeReadOnlyControlPlaneFlag() {
        SysRole operator = controlPlaneRole(3L);
        when(roleMapper.selectById(3L)).thenReturn(operator);
        when(crossTenantAuthority.isControlPlaneRole(operator)).thenReturn(true);
        when(permissionMapper.selectList(null)).thenReturn(List.of());
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        RoleVO role = service.get(3L);

        assertTrue(role.getControlPlane());
    }

    private SysRole createWith(String dataScope, boolean controlPlane) {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(controlPlane);
        service.create(request(dataScope));
        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(captor.capture());
        return captor.getValue();
    }

    private RoleSaveRequest request(String dataScope) {
        return request("test_role", dataScope);
    }

    private RoleSaveRequest request(String roleCode, String dataScope) {
        return new RoleSaveRequest("测试角色", roleCode, null, 1, dataScope, List.of());
    }

    private SysRole controlPlaneRole(Long id) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode("operator");
        role.setStatus(1);
        role.setControlPlane(SysRole.CONTROL_PLANE_ENABLED);
        return role;
    }
}
