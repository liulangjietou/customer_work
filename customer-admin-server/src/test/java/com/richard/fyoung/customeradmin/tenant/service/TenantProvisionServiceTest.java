package com.richard.fyoung.customeradmin.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.system.permission.entity.SysPermission;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.entity.SysRolePermission;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantProvisionServiceTest {

    @Test
    void provision_shouldGrantOnlyTenantSafePermissions() {
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysRolePermissionMapper rolePermissionMapper = mock(SysRolePermissionMapper.class);
        SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);
        when(permissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            permission(1L, "menu:view"),
            permission(2L, "sensitive-word:edit"),
            // billing 与 sql-console 分别代表"视野是全平台"与"内部运维工具"两类，都不该发给租户
            permission(3L, "billing:view"),
            permission(4L, "sql-console:query"),
            permission(5L, "agent:edit"),
            permission(6L, "role:edit")));
        when(roleMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            SysRole role = invocation.getArgument(0);
            role.setId(10L);
            return 1;
        }).when(roleMapper).insert(any(SysRole.class));

        new TenantProvisionService(roleMapper, rolePermissionMapper, permissionMapper).provision("tenant-a");

        ArgumentCaptor<SysRole> roleCaptor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(roleCaptor.capture());
        assertEquals(SysRole.CONTROL_PLANE_DISABLED, roleCaptor.getValue().getControlPlane());
        assertEquals(DataScope.TENANT.name(), roleCaptor.getValue().getDataScope());

        ArgumentCaptor<SysRolePermission> relationCaptor = ArgumentCaptor.forClass(SysRolePermission.class);
        verify(rolePermissionMapper, times(2)).insert(relationCaptor.capture());
        assertEquals(List.of(5L, 6L),
            relationCaptor.getAllValues().stream().map(SysRolePermission::getPermissionId).toList());
        assertEquals(List.of(10L, 10L),
            relationCaptor.getAllValues().stream().map(SysRolePermission::getRoleId).toList());
    }

    private SysPermission permission(Long id, String code) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setPermCode(code);
        return permission;
    }
}
