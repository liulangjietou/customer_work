package com.richard.fyoung.customeradmin.tenant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.Result;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.dto.TenantViewVO;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link TenantController} 的控制面角色与权限点双重门禁测试。 */
class TenantControllerTest {

    private TenantService tenantService;
    private CrossTenantAuthority crossTenantAuthority;
    private TenantController controller;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new TenantController(tenantService, crossTenantAuthority);
    }

    @Test
    void currentView_shouldReportRoleCapabilityIndependentlyFromTenantId() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::currentUserTenant).thenReturn("default");
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("default");

            Result<TenantViewVO> result = controller.currentView();

            assertEquals("default", result.getData().getUserTenantId());
            assertEquals("default", result.getData().getEffectiveTenantId());
            assertFalse(result.getData().getCrossTenantAuthority(),
                "归属 default 不能自动获得控制面能力");
        }
    }

    @Test
    void switchView_shouldRejectOrdinaryDefaultUserBeforeTenantLookup() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> controller.switchView("acme"));

        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
        verify(tenantService, never()).resolveAccessibleSnapshot("acme");
    }

    @Test
    void switchView_shouldAllowControlPlaneRoleAndValidateTarget() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(tenantService.resolveAccessibleSnapshot("acme"))
            .thenReturn(new TenantAccessSnapshot("AcMe", "ACTIVE", 7L, null));
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            controller.switchView("acme");

            verify(tenantService).resolveAccessibleSnapshot("acme");
            tenantSession.verify(() -> TenantSession.switchView("AcMe", 7L));
        }
    }

    @Test
    void switchView_shouldRetainTenantViewPermissionPoint() throws Exception {
        Method method = TenantController.class.getMethod("switchView", String.class);
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);

        assertTrue(permission != null, "跨租户切换必须保留接口权限点");
        assertArrayEquals(new String[]{"tenant:view"}, permission.value());
    }

    @Test
    void accessControlEndpoints_shouldReuseExistingTenantPermissions() throws Exception {
        SaCheckPermission revokePermission = TenantController.class
            .getMethod("revokeSessions", Long.class).getAnnotation(SaCheckPermission.class);
        SaCheckPermission deliveryPermission = TenantController.class
            .getMethod("accessDelivery", Long.class).getAnnotation(SaCheckPermission.class);

        assertArrayEquals(new String[]{"tenant:edit"}, revokePermission.value());
        assertArrayEquals(new String[]{"tenant:view"}, deliveryPermission.value());
    }
}
