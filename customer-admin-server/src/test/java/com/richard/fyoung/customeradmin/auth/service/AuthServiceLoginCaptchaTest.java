package com.richard.fyoung.customeradmin.auth.service;

import com.richard.fyoung.customeradmin.auth.config.AdminLdapProperties;
import com.richard.fyoung.customeradmin.auth.dto.LoginRequest;
import com.richard.fyoung.customeradmin.auth.dto.SsoLoginRequest;
import com.richard.fyoung.customeradmin.auth.guard.LoginAttemptGuard;
import com.richard.fyoung.customeradmin.auth.guard.LoginCaptchaService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.log.entity.SysOperationLog;
import com.richard.fyoung.customeradmin.system.log.mapper.OperationLogMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 登录 proof 的消费顺序与 OA 失败计数边界。 */
class AuthServiceLoginCaptchaTest {

    private static final String CLIENT_IP = "203.0.113.9";
    private static final String USER_AGENT = "Mozilla/5.0 Test";

    @Test
    void localLogin_invalidProof_shouldStopBeforeLockDatabaseAndBcrypt() {
        Dependencies dependencies = new Dependencies(false);
        doThrow(new BizException(ResultCode.LOGIN_CAPTCHA_INVALID))
            .when(dependencies.loginCaptchaService).consumeProof("bad-proof", CLIENT_IP, USER_AGENT);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.login(
            new LoginRequest("richard", "password", false, "bad-proof"), CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_CAPTCHA_INVALID, exception.getResultCode());
        verifyNoInteractions(dependencies.loginAttemptGuard, dependencies.userMapper,
            dependencies.passwordEncoder, dependencies.ldapAuthService);
    }

    @Test
    void localLogin_wrongPassword_shouldConsumeProofBeforeLockDatabaseAndBcrypt() {
        Dependencies dependencies = new Dependencies(false);
        SysUser user = activeUser("richard");
        user.setPassword("hash");
        when(dependencies.userMapper.selectOne(any())).thenReturn(user);
        when(dependencies.passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.login(
            new LoginRequest("richard", "wrong", false, "proof"), CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_FAILED, exception.getResultCode());
        InOrder ordered = inOrder(dependencies.loginCaptchaService, dependencies.loginAttemptGuard,
            dependencies.userMapper, dependencies.passwordEncoder);
        ordered.verify(dependencies.loginCaptchaService).consumeProof("proof", CLIENT_IP, USER_AGENT);
        ordered.verify(dependencies.loginAttemptGuard).checkNotLocked("richard", CLIENT_IP);
        ordered.verify(dependencies.userMapper).selectOne(any());
        ordered.verify(dependencies.passwordEncoder).matches("wrong", "hash");
        verify(dependencies.loginAttemptGuard).recordFailure("richard", CLIENT_IP);

        ArgumentCaptor<SysOperationLog> logCaptor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(dependencies.operationLogMapper).insert(logCaptor.capture());
        assertEquals(CLIENT_IP, logCaptor.getValue().getIp());
    }

    @Test
    void ssoLogin_disabled_shouldNotConsumeProofOrCallLdap() {
        Dependencies dependencies = new Dependencies(false);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.ssoLogin(
            new SsoLoginRequest("richard", "password", false, "proof"), CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.SSO_NOT_ENABLED, exception.getResultCode());
        verifyNoInteractions(dependencies.loginCaptchaService, dependencies.loginAttemptGuard,
            dependencies.ldapAuthService);
    }

    @Test
    void ssoLogin_invalidProof_shouldStopBeforeAttemptLockAndLdapBind() {
        Dependencies dependencies = new Dependencies(true);
        doThrow(new BizException(ResultCode.LOGIN_CAPTCHA_INVALID))
            .when(dependencies.loginCaptchaService).consumeProof("bad-proof", CLIENT_IP, USER_AGENT);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.ssoLogin(
            new SsoLoginRequest("richard@example.com", "password", false, "bad-proof"),
            CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.LOGIN_CAPTCHA_INVALID, exception.getResultCode());
        verifyNoInteractions(dependencies.loginAttemptGuard, dependencies.ldapAuthService);
    }

    @Test
    void ssoLogin_oversizedNormalizedUsername_shouldStopBeforeProofAndLdap() {
        Dependencies dependencies = new Dependencies(true);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.ssoLogin(
            new SsoLoginRequest("u".repeat(65) + "@example.com", "password", false, "proof"),
            CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
        verifyNoInteractions(dependencies.loginCaptchaService, dependencies.loginAttemptGuard,
            dependencies.ldapAuthService);
    }

    @Test
    void ssoLogin_serviceUnavailable_shouldConsumeBeforeBindWithoutCredentialFailure() {
        Dependencies dependencies = new Dependencies(true);
        when(dependencies.ldapAuthService.bind("richard", "password"))
            .thenReturn(LdapBindResult.SERVICE_UNAVAILABLE);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.ssoLogin(
            new SsoLoginRequest("richard@example.com", "password", false, "proof"),
            CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.SSO_SERVICE_UNAVAILABLE, exception.getResultCode());
        InOrder ordered = inOrder(dependencies.loginCaptchaService, dependencies.loginAttemptGuard,
            dependencies.ldapAuthService);
        ordered.verify(dependencies.loginCaptchaService).consumeProof("proof", CLIENT_IP, USER_AGENT);
        ordered.verify(dependencies.loginAttemptGuard).checkNotLocked("richard", CLIENT_IP);
        ordered.verify(dependencies.ldapAuthService).bind("richard", "password");
        verify(dependencies.loginAttemptGuard, never()).recordFailure(any(), any());
        verify(dependencies.loginAttemptGuard, never()).recordSuccess(any(), any());
    }

    @Test
    void ssoLogin_invalidCredentials_shouldRecordFailureAfterBind() {
        Dependencies dependencies = new Dependencies(true);
        when(dependencies.ldapAuthService.bind("richard", "wrong"))
            .thenReturn(LdapBindResult.INVALID_CREDENTIALS);

        BizException exception = assertThrows(BizException.class, () -> dependencies.service.ssoLogin(
            new SsoLoginRequest("richard", "wrong", false, "proof"), CLIENT_IP, USER_AGENT));

        assertEquals(ResultCode.SSO_LOGIN_FAILED, exception.getResultCode());
        InOrder ordered = inOrder(dependencies.loginCaptchaService, dependencies.loginAttemptGuard,
            dependencies.ldapAuthService);
        ordered.verify(dependencies.loginCaptchaService).consumeProof("proof", CLIENT_IP, USER_AGENT);
        ordered.verify(dependencies.loginAttemptGuard).checkNotLocked("richard", CLIENT_IP);
        ordered.verify(dependencies.ldapAuthService).bind("richard", "wrong");
        ordered.verify(dependencies.loginAttemptGuard).recordFailure("richard", CLIENT_IP);
    }

    private static SysUser activeUser(String username) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername(username);
        user.setStatus(1);
        user.setTenantId("default");
        return user;
    }

    private static final class Dependencies {
        private final SysUserMapper userMapper = mock(SysUserMapper.class);
        private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        private final OperationLogMapper operationLogMapper = mock(OperationLogMapper.class);
        private final LdapAuthService ldapAuthService = mock(LdapAuthService.class);
        private final LoginAttemptGuard loginAttemptGuard = mock(LoginAttemptGuard.class);
        private final LoginCaptchaService loginCaptchaService = mock(LoginCaptchaService.class);
        private final AuthService service;

        private Dependencies(boolean ldapEnabled) {
            AdminLdapProperties ldapProperties = new AdminLdapProperties();
            ldapProperties.setEnabled(ldapEnabled);
            service = new AuthService(userMapper, passwordEncoder, operationLogMapper, ldapAuthService,
                ldapProperties, mock(SysRoleMapper.class), mock(SysUserRoleMapper.class),
                mock(TenantService.class), mock(SessionRevocationService.class),
                loginAttemptGuard, loginCaptchaService);
        }
    }
}
