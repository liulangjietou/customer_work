package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.notify.RegistrationNotificationService;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.dto.TenantSaveRequest;
import com.richard.fyoung.customeradmin.tenant.service.TenantProvisionService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对外开放实例下的注册审核。
 *
 * <p>核心是一条边界：<b>注册者不能落进平台自用的 {@code default} 租户</b>。
 * 同一个租户内绝大多数配置资产（智能体、知识库、技能、MCP、渠道、SQL 配置、字典、敏感词）
 * 是共享的——见 {@code DataScopeTables} 的类注释，它们刻意不参与"仅本人"过滤。
 * 陌生人一旦落进 default，看到的就是平台自己的东西。</p>
 */
class UserApprovalPublicDeploymentTest {

    private static final String NEW_TENANT = "acme-corp";
    private static final Long TENANT_ADMIN_ROLE_ID = 77L;

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SysRoleMapper roleMapper;
    private CrossTenantAuthority crossTenantAuthority;
    private TenantService tenantService;
    private PublicDeploymentProperties publicDeployment;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        tenantService = mock(TenantService.class);
        publicDeployment = new PublicDeploymentProperties();
        publicDeployment.setEnabled(true);
        service = new UserService(
            userMapper, userRoleMapper, roleMapper, mock(PasswordEncoder.class),
            crossTenantAuthority, mock(SessionRevocationService.class), tenantService,
            publicDeployment, mock(RegistrationNotificationService.class));

        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(userMapper.updateById(any(SysUser.class))).thenReturn(1);
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);
        when(userMapper.selectById(9L)).thenReturn(pendingUser());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 并入 default 会让陌生人与平台共享同一批配置资产，对外实例必须拒绝。 */
    @Test
    void review_shouldRejectApprovingIntoPlatformDefaultTenant() {
        when(tenantService.resolveAccessibleSnapshot(TenantContext.DEFAULT))
            .thenReturn(new TenantAccessSnapshot(TenantContext.DEFAULT, "ACTIVE", 0L, null));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            BizException error = assertThrows(BizException.class, () -> service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.APPROVED, TenantContext.DEFAULT,
                    List.of(3L), null, null)));

            assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        }
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    /** 内网实例保留原有行为：并入 default 是单租户部署的常规做法。 */
    @Test
    void review_shouldAllowDefaultTenantOnInternalDeployment() {
        publicDeployment.setEnabled(false);
        when(tenantService.resolveAccessibleSnapshot(TenantContext.DEFAULT))
            .thenReturn(new TenantAccessSnapshot(TenantContext.DEFAULT, "ACTIVE", 0L, null));
        when(roleMapper.selectBatchIds(List.of(3L))).thenReturn(List.of(role(3L)));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            service.review(9L, new UserApprovalRequest(UserApprovalStatus.APPROVED,
                TenantContext.DEFAULT, List.of(3L), null, null));
        }

        verify(userMapper).updateById(any(SysUser.class));
    }

    /**
     * 顺带开租户：建租户 → 取刚生成的租户管理员角色 → 把人放进去。
     *
     * <p>角色 ID 不能由请求给出——那个角色是 {@code TenantProvisionService} 在建租户时
     * 刚插入的，审核人无从得知它的主键。</p>
     */
    @Test
    void review_shouldProvisionTenantAndBindGeneratedTenantAdminRole() {
        stubProvisionedTenant();

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            service.review(9L, new UserApprovalRequest(UserApprovalStatus.APPROVED, null, null, "已核验",
                new UserApprovalRequest.NewTenant(NEW_TENANT, "ACME 公司", null)));
        }

        ArgumentCaptor<TenantSaveRequest> tenantCaptor = ArgumentCaptor.forClass(TenantSaveRequest.class);
        verify(tenantService).create(tenantCaptor.capture());
        assertEquals(NEW_TENANT, tenantCaptor.getValue().getTenantCode());
        assertEquals("ACME 公司", tenantCaptor.getValue().getTenantName());
        // 联系邮箱留空时取注册人的邮箱，省掉审核人再抄一遍
        assertEquals("richard@example.com", tenantCaptor.getValue().getContactEmail());

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertEquals(NEW_TENANT, userCaptor.getValue().getTenantId());
        assertEquals(UserApprovalStatus.APPROVED.name(), userCaptor.getValue().getApprovalStatus());

        ArgumentCaptor<SysUserRole> roleCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(roleCaptor.capture());
        assertEquals(TENANT_ADMIN_ROLE_ID, roleCaptor.getValue().getRoleId());
    }

    /** 开租户是控制面动作，普通租户审核人不能借它给自己开一个新隔离域。 */
    @Test
    void review_shouldRequireCrossTenantAuthorityToProvisionTenant() {
        org.mockito.Mockito.doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            BizException error = assertThrows(BizException.class, () -> service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.APPROVED, null, null, null,
                    new UserApprovalRequest.NewTenant(NEW_TENANT, "ACME 公司", null))));

            assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, error.getResultCode());
        }
        verify(tenantService, never()).create(any(TenantSaveRequest.class));
    }

    /** 拒绝申请时不该顺带开一个空租户出来。 */
    @Test
    void review_shouldRejectProvisioningTenantWhenDecisionIsRejected() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            BizException error = assertThrows(BizException.class, () -> service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.REJECTED, null, null, "资料不全",
                    new UserApprovalRequest.NewTenant(NEW_TENANT, "ACME 公司", null))));

            assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        }
        verify(tenantService, never()).create(any(TenantSaveRequest.class));
    }

    /**
     * provision 刚跑过却取不到角色，说明租户初始化没落地。
     *
     * <p>继续下去会造出一个归属新租户、却没有任何权限的账号——用户能登录、什么都看不到，
     * 而审核人以为已经开通了。这种"静默半成品"必须当场失败。</p>
     */
    @Test
    void review_shouldFailFastWhenProvisionedTenantHasNoAdminRole() {
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(99L);

            BizException error = assertThrows(BizException.class, () -> service.review(9L,
                new UserApprovalRequest(UserApprovalStatus.APPROVED, null, null, null,
                    new UserApprovalRequest.NewTenant(NEW_TENANT, "ACME 公司", null))));

            assertEquals(ResultCode.SYSTEM_ERROR, error.getResultCode());
        }
        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    private void stubProvisionedTenant() {
        when(tenantService.create(any(TenantSaveRequest.class))).thenReturn(5L);
        SysRole tenantAdmin = role(TENANT_ADMIN_ROLE_ID);
        tenantAdmin.setRoleCode(TenantProvisionService.TENANT_ADMIN_ROLE_CODE);
        when(roleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(tenantAdmin));
        when(tenantService.resolveAccessibleSnapshot(anyString()))
            .thenReturn(new TenantAccessSnapshot(NEW_TENANT, "ACTIVE", 0L, null));
    }

    private SysUser pendingUser() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setNickname("Richard");
        user.setEmail("richard@example.com");
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
}
