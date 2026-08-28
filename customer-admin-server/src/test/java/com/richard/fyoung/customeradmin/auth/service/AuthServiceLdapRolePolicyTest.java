package com.richard.fyoung.customeradmin.auth.service;

import com.richard.fyoung.customeradmin.auth.guard.LoginAttemptGuard;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.auth.config.AdminLdapProperties;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LDAP 首登默认角色不能成为控制面提权入口。 */
class AuthServiceLdapRolePolicyTest {

    @Test
    void properties_shouldNotGrantAnyRoleByDefault() {
        assertTrue(new AdminLdapProperties().getDefaultRoleCodes().isEmpty());
    }

    @Test
    void configuredControlPlaneRole_shouldBeRejectedBeforeAssignment() {
        AdminLdapProperties properties = new AdminLdapProperties();
        properties.setDefaultRoleCodes(List.of("super_admin"));
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysRole superAdmin = new SysRole();
        superAdmin.setId(1L);
        superAdmin.setRoleCode("super_admin");
        superAdmin.setStatus(1);
        superAdmin.setControlPlane(SysRole.CONTROL_PLANE_ENABLED);
        when(roleMapper.selectList(any())).thenReturn(List.of(superAdmin));
        AuthService service = new AuthService(
            mock(SysUserMapper.class), mock(PasswordEncoder.class), mock(OperationLogMapper.class),
            mock(LdapAuthService.class), properties, roleMapper, userRoleMapper, mock(TenantService.class),
            mock(SessionRevocationService.class),
            mock(LoginAttemptGuard.class), mock(LoginCaptchaService.class));

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "resolveAssignableDefaultRoles"));

        assertEquals(ResultCode.FORBIDDEN, exception.getResultCode());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void missingConfiguredDefaultRole_shouldFailBeforeCreatingLdapUser() {
        AdminLdapProperties properties = new AdminLdapProperties();
        properties.setDefaultRoleCodes(List.of("tenant_operator"));
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        when(roleMapper.selectList(any())).thenReturn(List.of());
        AuthService service = new AuthService(
            mock(SysUserMapper.class), mock(PasswordEncoder.class), mock(OperationLogMapper.class),
            mock(LdapAuthService.class), properties, roleMapper, mock(SysUserRoleMapper.class),
            mock(TenantService.class), mock(SessionRevocationService.class),
            mock(LoginAttemptGuard.class), mock(LoginCaptchaService.class));

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "resolveAssignableDefaultRoles"));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
    }

    @Test
    void disabledConfiguredDefaultRole_shouldFailBeforeCreatingLdapUser() {
        AdminLdapProperties properties = new AdminLdapProperties();
        properties.setDefaultRoleCodes(List.of("tenant_operator"));
        SysRole role = new SysRole();
        role.setRoleCode("tenant_operator");
        role.setStatus(0);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        when(roleMapper.selectList(any())).thenReturn(List.of(role));
        AuthService service = new AuthService(
            mock(SysUserMapper.class), mock(PasswordEncoder.class), mock(OperationLogMapper.class),
            mock(LdapAuthService.class), properties, roleMapper, mock(SysUserRoleMapper.class),
            mock(TenantService.class), mock(SessionRevocationService.class),
            mock(LoginAttemptGuard.class), mock(LoginCaptchaService.class));

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "resolveAssignableDefaultRoles"));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
    }

    @Test
    void activeLocalAccountWithSameUsername_shouldNotBeLinkedToLdapIdentity() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        when(userMapper.selectOne(any())).thenReturn(localUser());
        AuthService service = service(userMapper, userRoleMapper);

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "doFindOrCreateLdapUser", "admin"));

        assertEquals(ResultCode.SSO_LOGIN_FAILED, exception.getResultCode());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void deletedLocalAccountWithSameUsername_shouldNotBeRevivedAsLdapIdentity() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectByUsernameIgnoreLogicDelete("admin")).thenReturn(localUser());
        AuthService service = service(userMapper, userRoleMapper);

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "doFindOrCreateLdapUser", "admin"));

        assertEquals(ResultCode.SSO_LOGIN_FAILED, exception.getResultCode());
        verify(userMapper, never()).reviveDeletedUser(any(), any());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    @Test
    void concurrentLocalAccountCreation_shouldNotBeLinkedAfterDuplicateInsert() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        when(userMapper.selectOne(any())).thenReturn(null, localUser());
        when(userMapper.selectByUsernameIgnoreLogicDelete("admin")).thenReturn(null);
        doThrow(new DuplicateKeyException("duplicate username"))
            .when(userMapper).insert(any(SysUser.class));
        AuthService service = service(userMapper, userRoleMapper);

        BizException exception = assertThrows(BizException.class,
            () -> ReflectionTestUtils.invokeMethod(service, "doFindOrCreateLdapUser", "admin"));

        assertEquals(ResultCode.SSO_LOGIN_FAILED, exception.getResultCode());
        verify(userRoleMapper, never()).insert(any(SysUserRole.class));
    }

    private AuthService service(SysUserMapper userMapper, SysUserRoleMapper userRoleMapper) {
        return new AuthService(
            userMapper,
            mock(PasswordEncoder.class),
            mock(OperationLogMapper.class),
            mock(LdapAuthService.class),
            new AdminLdapProperties(),
            mock(SysRoleMapper.class),
            userRoleMapper,
            mock(TenantService.class),
            mock(SessionRevocationService.class),
            mock(LoginAttemptGuard.class), mock(LoginCaptchaService.class));
    }

    private SysUser localUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setLoginType("LOCAL");
        user.setStatus(1);
        return user;
    }
}
