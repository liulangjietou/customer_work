package com.richard.fyoung.customeradmin.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link UserService} 的控制面角色分配防提权测试。 */
class UserServiceControlPlaneTest {

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private CrossTenantAuthority crossTenantAuthority;
    private SessionRevocationService sessionRevocationService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        sessionRevocationService = mock(SessionRevocationService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        service = new UserService(
            userMapper, userRoleMapper, roleMapper, passwordEncoder, crossTenantAuthority,
            sessionRevocationService);
    }

    @Test
    void create_shouldRejectControlPlaneRoleAssignmentBeforeCreatingUser() {
        SysRole operator = role(3L, true);
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(operator));
        when(crossTenantAuthority.hasAuthority(List.of(operator))).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> service.create(request("new-user", "password", List.of(3L))));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void update_shouldRejectRemovingExistingControlPlaneRoleBeforeUpdatingUser() {
        SysUser user = user(9L);
        SysRole operator = role(3L, true);
        SysUserRole relation = new SysUserRole();
        relation.setUserId(9L);
        relation.setRoleId(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(operator));
        when(crossTenantAuthority.hasAuthority(List.of(operator))).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> service.update(9L, request("ignored", "", List.of())));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void update_shouldAllowControlPlaneUserToAssignControlPlaneRole() {
        SysUser user = user(9L);
        SysRole operator = role(3L, true);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(operator));
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(crossTenantAuthority.hasAuthority(List.of(operator))).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);

        service.update(9L, request("ignored", "", List.of(3L)));

        verify(userMapper).updateById(user);
        verify(userMapper).incrementAuthEpoch(9L);
        verify(sessionRevocationService).revokeUserAfterCommit(9L);
        verify(userRoleMapper).insert(any(SysUserRole.class));
    }

    @Test
    void delete_shouldRejectDeletingControlPlaneUserBeforeDeletingUser() {
        SysUser user = user(9L);
        SysRole operator = role(3L, true);
        SysUserRole relation = new SysUserRole();
        relation.setUserId(9L);
        relation.setRoleId(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(operator));
        when(crossTenantAuthority.hasAuthority(List.of(operator))).thenReturn(true);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> service.delete(9L));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(userMapper, never()).deleteById(9L);
    }

    private UserSaveRequest request(String username, String password, List<Long> roleIds) {
        return new UserSaveRequest(username, password, "测试用户", 1, roleIds);
    }

    private SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("existing");
        user.setStatus(1);
        user.setApprovalStatus(UserApprovalStatus.APPROVED.name());
        return user;
    }

    private SysRole role(Long id, boolean controlPlane) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode("operator");
        role.setStatus(1);
        role.setControlPlane(controlPlane ? SysRole.CONTROL_PLANE_ENABLED : 0);
        return role;
    }
}
