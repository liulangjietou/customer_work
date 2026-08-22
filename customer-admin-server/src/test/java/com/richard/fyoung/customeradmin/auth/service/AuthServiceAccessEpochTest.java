package com.richard.fyoung.customeradmin.auth.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.auth.config.AdminLdapProperties;
import com.richard.fyoung.customeradmin.auth.dto.ChangePasswordRequest;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceAccessEpochTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void login_shouldBindUserAndTenantEpochsIntoSession() {
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.requireAccessibleSnapshot("acme"))
            .thenReturn(new TenantAccessSnapshot("acme", "ACTIVE", 8L, null));
        AuthService service = service(
            mock(SysUserMapper.class), mock(PasswordEncoder.class), tenantService,
            mock(SessionRevocationService.class));
        SysUser user = user();
        SaSession tokenSession = mock(SaSession.class);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            stpUtil.when(StpUtil::getTokenSession).thenReturn(tokenSession);

            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "doLogin", user, false));

            stpUtil.verify(() -> StpUtil.login(9L));
            verify(tokenSession).set("username", "richard");
            tenantSession.verify(() -> TenantSession.bindTenant("acme", 7L, 8L));
        }
    }

    @Test
    void changePassword_shouldRotateEpochAndRevokeSessions() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        SessionRevocationService revocationService = mock(SessionRevocationService.class);
        SysUser user = user();
        user.setPassword("old-hash");
        when(userMapper.selectById(9L)).thenReturn(user);
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);
        when(encoder.matches("old-password", "old-hash")).thenReturn(true);
        when(encoder.encode("new-password")).thenReturn("new-hash");
        AuthService service = service(userMapper, encoder, mock(TenantService.class), revocationService);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(9L);

            service.changePassword(new ChangePasswordRequest("old-password", "new-password"));

            verify(userMapper).incrementAuthEpoch(9L);
            verify(revocationService).revokeUserAfterCommit(9L);
        }
    }

    private AuthService service(SysUserMapper userMapper, PasswordEncoder encoder,
                                TenantService tenantService, SessionRevocationService revocationService) {
        return new AuthService(
            userMapper,
            encoder,
            mock(OperationLogMapper.class),
            mock(LdapAuthService.class),
            new AdminLdapProperties(),
            mock(SysRoleMapper.class),
            mock(SysUserRoleMapper.class),
            tenantService,
            revocationService);
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setTenantId("acme");
        user.setStatus(1);
        user.setAuthEpoch(7L);
        return user;
    }
}
