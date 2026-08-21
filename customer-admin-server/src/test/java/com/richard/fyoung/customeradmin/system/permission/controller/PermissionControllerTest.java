package com.richard.fyoung.customeradmin.system.permission.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionVO;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 全局权限字典写接口的控制面门禁测试。 */
class PermissionControllerTest {

    private PermissionService permissionService;
    private CrossTenantAuthority crossTenantAuthority;
    private PermissionController controller;

    @BeforeEach
    void setUp() {
        permissionService = mock(PermissionService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new PermissionController(permissionService, crossTenantAuthority);
    }

    @Test
    void writeEndpoints_shouldRejectOrdinaryTenantBeforeChangingGlobalDictionary() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();
        PermissionSaveRequest request = request();

        assertForbidden(() -> controller.create(request));
        assertForbidden(() -> controller.update(1L, request));
        assertForbidden(() -> controller.delete(1L));
        verifyNoInteractions(permissionService);
    }

    @Test
    void writeEndpoints_shouldAllowControlPlaneUser() {
        PermissionSaveRequest request = request();

        controller.create(request);
        controller.update(1L, request);
        controller.delete(1L);

        verify(crossTenantAuthority, times(3)).requireCurrentUserAuthority();
        verify(permissionService).create(request);
        verify(permissionService).update(1L, request);
        verify(permissionService).delete(1L);
    }

    @Test
    void tree_shouldHideControlPlanePermissionsFromOrdinaryTenant() {
        PermissionVO billing = node("billing:view");
        billing.setChildren(List.of(node("billing:quota-edit"), node("billing:view-own")));
        when(permissionService.tree()).thenReturn(List.of(node("menu"), billing));
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        List<PermissionVO> result = controller.tree().getData();

        assertEquals(1, result.size());
        assertEquals("billing:view", result.get(0).getPermCode());
        assertEquals(List.of("billing:view-own"),
            result.get(0).getChildren().stream().map(PermissionVO::getPermCode).toList());
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }

    private PermissionSaveRequest request() {
        return new PermissionSaveRequest(0L, "测试权限", "test:edit", 2, null, null, null, 1);
    }

    private PermissionVO node(String permissionCode) {
        PermissionVO node = new PermissionVO();
        node.setPermCode(permissionCode);
        return node;
    }
}
