package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPolicyService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link TenantContextInterceptor} 的会话降权边界测试。 */
class TenantContextInterceptorTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void preHandle_shouldClearStaleCrossTenantViewAfterAuthorityRevoked() {
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        TenantAccessPolicyService accessPolicy = mock(TenantAccessPolicyService.class);
        when(authority.hasCurrentUserAuthority()).thenReturn(false);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(authority, accessPolicy);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(TenantContext.DEFAULT);
            tenantSession.when(TenantSession::currentViewTenant).thenReturn("acme");
            tenantSession.when(TenantSession::currentAuthEpoch).thenReturn(0L);
            tenantSession.when(TenantSession::currentTenantAccessEpoch).thenReturn(0L);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

            assertTrue(interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            tenantSession.verify(TenantSession::clearView);
            assertEquals(TenantContext.DEFAULT, TenantContext.get());
        }
    }

    @Test
    void preHandle_shouldKeepCrossTenantViewForCurrentControlPlaneRole() {
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        TenantAccessPolicyService accessPolicy = mock(TenantAccessPolicyService.class);
        when(authority.hasCurrentUserAuthority()).thenReturn(true);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(authority, accessPolicy);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(TenantContext.DEFAULT);
            tenantSession.when(TenantSession::currentViewTenant).thenReturn("acme");
            tenantSession.when(TenantSession::currentAuthEpoch).thenReturn(0L);
            tenantSession.when(TenantSession::currentTenantAccessEpoch).thenReturn(0L);
            tenantSession.when(TenantSession::currentViewTenantAccessEpoch).thenReturn(3L);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);

            assertTrue(interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            assertEquals("acme", TenantContext.get());
        }
        verify(authority).hasCurrentUserAuthority();
    }

    @Test
    void preHandle_shouldExpirePersistedSessionWithInvalidTenant() {
        TenantContextInterceptor interceptor = new TenantContextInterceptor(
            mock(CrossTenantAuthority.class), mock(TenantAccessPolicyService.class));

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn("_legacy");

            assertThrows(BizException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            stpUtil.verify(StpUtil::logout);
            assertFalse(TenantContext.isPresent());
        }
    }

    @Test
    void preHandle_shouldExpireLegacyLoggedInSessionWithoutTenant() {
        TenantContextInterceptor interceptor = new TenantContextInterceptor(
            mock(CrossTenantAuthority.class), mock(TenantAccessPolicyService.class));

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(null);
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            BizException exception = assertThrows(BizException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            assertEquals(ResultCode.TOKEN_EXPIRED, exception.getResultCode());
            stpUtil.verify(StpUtil::logout);
            assertFalse(TenantContext.isPresent());
        }
    }

    @Test
    void preHandle_shouldLogoutWhenAuthEpochIsStale() {
        TenantAccessPolicyService accessPolicy = mock(TenantAccessPolicyService.class);
        doThrow(new BizException(ResultCode.TOKEN_EXPIRED)).when(accessPolicy)
            .assertUserSessionAccessible(9L, "acme", 1L, 2L);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(
            mock(CrossTenantAuthority.class), accessPolicy);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn("acme");
            tenantSession.when(TenantSession::currentAuthEpoch).thenReturn(1L);
            tenantSession.when(TenantSession::currentTenantAccessEpoch).thenReturn(2L);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(9L);

            BizException exception = assertThrows(BizException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            assertEquals(ResultCode.TOKEN_EXPIRED, exception.getResultCode());
            stpUtil.verify(StpUtil::logout);
            assertFalse(TenantContext.isPresent());
        }
    }
}
