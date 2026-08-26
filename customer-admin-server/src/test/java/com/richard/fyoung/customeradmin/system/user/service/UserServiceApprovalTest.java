package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalOptionsVO;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.dto.TenantVO;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceApprovalTest {

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private CrossTenantAuthority crossTenantAuthority;
    private SessionRevocationService revocationService;
    private TenantService tenantService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        revocationService = mock(SessionRevocationService.class);
        tenantService = mock(TenantService.class);
        service = new UserService(
            userMapper,
            userRoleMapper,
            roleMapper,
            mock(PasswordEncoder.class),
            crossTenantAuthority,
            revocationService,
            tenantService);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);
        when(tenantService.resolveAccessibleSnapshot(TenantContext.DEFAULT))
            .thenReturn(snapshot(TenantContext.DEFAULT));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void review_shouldApproveAndAssignRoleAtomicallyThenRevokeOldSession() {
        SysUser user = pendingUser();
        SysRole role = role(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            service.review(9L, new UserApprovalRequest(
                UserApprovalStatus.APPROVED, TenantContext.DEFAULT, List.of(3L), " 已核验 "));
        }

        assertEquals(UserApprovalStatus.APPROVED.name(), user.getApprovalStatus());
        assertEquals(TenantContext.DEFAULT, user.getTenantId());
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
    void review_shouldMoveUserAndRoleRelationIntoSelectedTenant() {
        SysUser user = pendingUser();
        SysRole targetRole = role(30L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(tenantService.resolveAccessibleSnapshot("tenant-a")).thenReturn(snapshot("tenant-a"));
        when(roleMapper.selectBatchIds(List.of(30L))).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return List.of(targetRole);
        });
        when(userMapper.updateById(user)).thenAnswer(invocation -> {
            assertEquals(TenantContext.DEFAULT, TenantContext.require());
            return 1;
        });
        when(userMapper.incrementAuthEpoch(9L)).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return 1;
        });
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return 1;
        }).when(userRoleMapper).insert(any(SysUserRole.class));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.review(9L, new UserApprovalRequest(
                UserApprovalStatus.APPROVED, "tenant-a", List.of(30L), null));
        }

        assertEquals("tenant-a", user.getTenantId());
        verify(crossTenantAuthority).requireCurrentUserAuthority();
        verify(revocationService).revokeUserAfterCommit(9L);
        assertFalse(TenantContext.isPresent(), "审核结束后不能泄漏目标租户上下文");
    }

    @Test
    void review_shouldRejectUnauthorizedCrossTenantBindingBeforeMutation() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());
        when(tenantService.resolveAccessibleSnapshot("tenant-a")).thenReturn(snapshot("tenant-a"));
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();

        BizException error = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(UserApprovalStatus.APPROVED, "tenant-a", List.of(30L), null)));

        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void review_shouldRejectDisabledRoleBeforeMutation() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());
        SysRole disabled = role(3L);
        disabled.setStatus(0);
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(disabled));

        BizException error = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(
                UserApprovalStatus.APPROVED, TenantContext.DEFAULT, List.of(3L), null)));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void approvalOptions_shouldReturnTargetTenantRolesWithoutLeakingContext() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(tenantService.listActive()).thenReturn(List.of(
            tenant(TenantContext.DEFAULT, "默认租户"), tenant("tenant-a", "租户 A")));
        when(tenantService.resolveAccessibleSnapshot("tenant-a")).thenReturn(snapshot("tenant-a"));
        SysRole role = role(30L);
        role.setRoleName("租户管理员");
        role.setRoleCode("tenant_admin");
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            return List.of(role);
        });

        UserApprovalOptionsVO options = TenantContext.callWith(TenantContext.DEFAULT,
            () -> service.approvalOptions("tenant-a"));

        assertEquals("tenant-a", options.selectedTenantId());
        assertEquals(List.of(TenantContext.DEFAULT, "tenant-a"),
            options.tenants().stream().map(UserApprovalOptionsVO.TenantOption::tenantId).toList());
        assertEquals(List.of(30L),
            options.roles().stream().map(UserApprovalOptionsVO.RoleOption::id).toList());
        assertFalse(TenantContext.isPresent(), "选项查询结束后不能泄漏目标租户上下文");
    }

    @Test
    void approvalOptions_shouldHideOtherTenantsFromOrdinaryReviewer() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        when(tenantService.listActive()).thenReturn(List.of(
            tenant(TenantContext.DEFAULT, "默认租户"), tenant("tenant-a", "租户 A")));
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(role(3L)));

        UserApprovalOptionsVO options = TenantContext.callWith(TenantContext.DEFAULT,
            () -> service.approvalOptions(TenantContext.DEFAULT));

        assertEquals(List.of(TenantContext.DEFAULT),
            options.tenants().stream().map(UserApprovalOptionsVO.TenantOption::tenantId).toList());
    }

    @Test
    void approvalOptions_shouldRejectCrossTenantEnumerationBeforeLookup() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        when(tenantService.listActive()).thenReturn(List.of(tenant(TenantContext.DEFAULT, "默认租户")));

        BizException error = assertThrows(BizException.class,
            () -> TenantContext.callWith(TenantContext.DEFAULT,
                () -> service.approvalOptions("tenant-a")));

        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, error.getResultCode());
        verify(tenantService, never()).resolveAccessibleSnapshot("tenant-a");
        verify(roleMapper, never()).selectList(any(LambdaQueryWrapper.class));
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
    void review_shouldRequireTenantAndAtLeastOneRoleWhenApproved() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());

        BizException missingTenant = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(UserApprovalStatus.APPROVED, null, List.of(3L), null)));
        BizException missingRole = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(
                UserApprovalStatus.APPROVED, TenantContext.DEFAULT, List.of(), null)));

        assertEquals(ResultCode.PARAM_MISSING, missingTenant.getResultCode());
        assertEquals(ResultCode.PARAM_MISSING, missingRole.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void review_shouldRejectAndClearExistingRolesWithoutMovingTenant() {
        SysUser user = pendingUser();
        SysUserRole existing = new SysUserRole();
        existing.setUserId(9L);
        existing.setRoleId(3L);
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(existing));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);
            service.review(9L, new UserApprovalRequest(
                UserApprovalStatus.REJECTED, TenantContext.DEFAULT, List.of(3L), "资料不完整"));
        }

        assertEquals(UserApprovalStatus.REJECTED.name(), user.getApprovalStatus());
        assertEquals(TenantContext.DEFAULT, user.getTenantId());
        assertEquals("资料不完整", user.getApprovalRemark());
        verify(userRoleMapper).delete(any(LambdaQueryWrapper.class));
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
        verify(userMapper).incrementAuthEpoch(9L);
        verify(revocationService).revokeUserAfterCommit(9L);
    }

    @Test
    void review_shouldNotMoveTenantWhenRejected() {
        when(userMapper.selectById(9L)).thenReturn(pendingUser());

        BizException error = assertThrows(BizException.class, () -> service.review(9L,
            new UserApprovalRequest(UserApprovalStatus.REJECTED, "tenant-a", List.of(), null)));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(userMapper, never()).updateById(any(SysUser.class));
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
        user.setTenantId(TenantContext.DEFAULT);
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

    private TenantAccessSnapshot snapshot(String tenantId) {
        return new TenantAccessSnapshot(tenantId, "ACTIVE", 0L, null);
    }

    private TenantVO tenant(String tenantId, String tenantName) {
        TenantVO tenant = new TenantVO();
        tenant.setTenantCode(tenantId);
        tenant.setTenantName(tenantName);
        tenant.setStatus("ACTIVE");
        return tenant;
    }
}
