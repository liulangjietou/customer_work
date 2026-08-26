package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceApprovalTest {

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private SessionRevocationService revocationService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        revocationService = mock(SessionRevocationService.class);
        service = new UserService(
            userMapper,
            userRoleMapper,
            roleMapper,
            mock(PasswordEncoder.class),
            mock(CrossTenantAuthority.class),
            revocationService);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);
    }

    @Test
    void review_shouldApproveAndAssignRoleAtomicallyThenRevokeOldSession() {
        SysUser user = pendingUser();
        SysRole role = role(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.APPROVED, List.of(3L), " 已核验 "));
        }

        assertEquals(UserApprovalStatus.APPROVED.name(), user.getApprovalStatus());
        assertEquals(99L, user.getApprovalBy());
        assertEquals("已核验", user.getApprovalRemark());
        assertNotNull(user.getApprovalTime());
        ArgumentCaptor<SysUserRole> relationCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(relationCaptor.capture());
        assertEquals(9L, relationCaptor.getValue().getUserId());
        assertEquals(3L, relationCaptor.getValue().getRoleId());
        verify(userMapper).incrementAuthEpoch(9L);
        verify(revocationService).revokeUserAfterCommit(9L);
    }

    @Test
    void create_shouldKeepAdminCreatedUserApproved() {
        UserSaveRequest request = new UserSaveRequest(
            "managed-user", "secret12", "Managed User", 1, List.of());

        service.create(request);

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        assertEquals(UserApprovalStatus.APPROVED.name(), userCaptor.getValue().getApprovalStatus());
    }

    @Test
    void review_shouldRequireAtLeastOneRoleWhenApproved() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());

        BizException error = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(UserApprovalStatus.APPROVED, List.of(), null)));

        assertEquals(ResultCode.PARAM_MISSING, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void review_shouldRejectAndClearExistingRolesThenRevokeOldSession() {
        SysUser user = pendingUser();
        SysUserRole existing = new SysUserRole();
        existing.setUserId(9L);
        existing.setRoleId(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.REJECTED, List.of(3L), "资料不完整"));
        }

        assertEquals(UserApprovalStatus.REJECTED.name(), user.getApprovalStatus());
        assertEquals("资料不完整", user.getApprovalRemark());
        verify(userRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
        verify(userMapper).incrementAuthEpoch(9L);
        verify(revocationService).revokeUserAfterCommit(9L);
    }

    @Test
    void update_shouldNotAssignRolesBeforeApproval() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role(3L)));

        BizException error = assertThrows(BizException.class, () -> service.update(9L,
            new UserSaveRequest("richard", null, "Richard", 1, List.of(3L))));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    private SysUser pendingUser() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setStatus(1);
        user.setApprovalStatus(UserApprovalStatus.PENDING.name());
        return user;
    }

    private SysRole role(Long id) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setStatus(1);
        role.setControlPlane(SysRole.CONTROL_PLANE_DISABLED);
        return role;
    }
}
