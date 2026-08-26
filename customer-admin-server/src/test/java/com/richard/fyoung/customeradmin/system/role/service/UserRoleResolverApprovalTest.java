package com.richard.fyoung.customeradmin.system.role.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRoleResolverApprovalTest {

    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private SysUserMapper userMapper;
    private UserRoleResolver resolver;

    @BeforeEach
    void setUp() {
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        userMapper = mock(SysUserMapper.class);
        resolver = new UserRoleResolver(userRoleMapper, roleMapper, userMapper);
    }

    @Test
    void enabledRolesOf_shouldReturnNoRolesBeforeApprovalEvenIfRelationExists() {
        when(userMapper.selectById(9L)).thenReturn(user(UserApprovalStatus.PENDING));

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            assertEquals(List.of(), resolver.enabledRolesOf(9L));
        }

        verify(userRoleMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(roleMapper, never()).selectBatchIds(any());
    }

    @Test
    void enabledRolesOf_shouldFailClosedWhenApprovalStatusIsMissing() {
        SysUser user = new SysUser();
        user.setId(9L);
        when(userMapper.selectById(9L)).thenReturn(user);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            assertEquals(List.of(), resolver.enabledRolesOf(9L));
        }

        verify(userRoleMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void enabledRolesOf_shouldResolveEnabledRolesAfterApproval() {
        SysUserRole relation = new SysUserRole();
        relation.setRoleId(3L);
        SysRole role = new SysRole();
        role.setId(3L);
        role.setStatus(1);
        when(userMapper.selectById(9L)).thenReturn(user(UserApprovalStatus.APPROVED));
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role));

        List<SysRole> roles;
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            roles = resolver.enabledRolesOf(9L);
        }

        assertEquals(List.of(role), roles);
    }

    private SysUser user(UserApprovalStatus status) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setApprovalStatus(status.name());
        return user;
    }
}
