package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
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
        when(authority.hasCurrentUserAuthority()).thenReturn(false);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(authority);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(TenantContext.DEFAULT);
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("acme");

            assertTrue(interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            tenantSession.verify(() -> TenantSession.switchView(null));
            assertEquals(TenantContext.DEFAULT, TenantContext.get());
        }
    }

    @Test
    void preHandle_shouldKeepCrossTenantViewForCurrentControlPlaneRole() {
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        when(authority.hasCurrentUserAuthority()).thenReturn(true);
        TenantContextInterceptor interceptor = new TenantContextInterceptor(authority);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn(TenantContext.DEFAULT);
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("acme");

            assertTrue(interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            assertEquals("acme", TenantContext.get());
        }
        verify(authority).hasCurrentUserAuthority();
    }

    @Test
    void preHandle_shouldExpirePersistedSessionWithInvalidTenant() {
        TenantContextInterceptor interceptor = new TenantContextInterceptor(mock(CrossTenantAuthority.class));

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn("_legacy");

            assertThrows(BizException.class, () -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), new Object()));

            stpUtil.verify(StpUtil::logout);
            assertFalse(TenantContext.isPresent());
        }
    }
}
