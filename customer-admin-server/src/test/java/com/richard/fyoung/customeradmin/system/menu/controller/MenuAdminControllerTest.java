package com.richard.fyoung.customeradmin.system.menu.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.menu.dto.MenuReorderRequest;
import com.richard.fyoung.customeradmin.system.menu.service.MenuChangeLogService;
import com.richard.fyoung.customeradmin.system.menu.service.MenuIconStorageService;
import com.richard.fyoung.customeradmin.system.permission.dto.PermissionSaveRequest;
import com.richard.fyoung.customeradmin.system.permission.service.PermissionService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 全局菜单字典写接口的控制面门禁测试。 */
class MenuAdminControllerTest {

    private PermissionService permissionService;
    private MenuIconStorageService iconStorageService;
    private CrossTenantAuthority crossTenantAuthority;
    private MenuAdminController controller;

    @BeforeEach
    void setUp() {
        permissionService = mock(PermissionService.class);
        iconStorageService = mock(MenuIconStorageService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new MenuAdminController(
            permissionService,
            mock(MenuChangeLogService.class),
            iconStorageService,
            crossTenantAuthority);
    }

    @Test
    void globalWriteEndpoints_shouldRejectOrdinaryTenant() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();
        PermissionSaveRequest request = request();
        MenuReorderRequest reorderRequest = reorderRequest();
        MultipartFile icon = mock(MultipartFile.class);

        assertForbidden(() -> controller.create(request));
        assertForbidden(() -> controller.update(1L, request));
        assertForbidden(() -> controller.delete(1L));
        assertForbidden(() -> controller.reorder(reorderRequest));
        assertForbidden(controller::publish);
        assertForbidden(() -> controller.uploadIcon(icon));
        verifyNoInteractions(permissionService);
        verifyNoInteractions(iconStorageService);
    }

    @Test
    void globalWriteEndpoints_shouldAllowControlPlaneUser() {
        PermissionSaveRequest request = request();
        MenuReorderRequest reorderRequest = reorderRequest();
        MultipartFile icon = mock(MultipartFile.class);

        controller.create(request);
        controller.update(1L, request);
        controller.delete(1L);
        controller.reorder(reorderRequest);
        controller.publish();
        controller.uploadIcon(icon);

        verify(crossTenantAuthority, times(6)).requireCurrentUserAuthority();
        verify(permissionService).create(request);
        verify(permissionService).update(1L, request);
        verify(permissionService).delete(1L);
        verify(permissionService).reorder(reorderRequest);
        verify(permissionService).publish();
        verify(iconStorageService).upload(icon);
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }

    private PermissionSaveRequest request() {
        return new PermissionSaveRequest(0L, "测试菜单", "test:view", 1, "/test", null, null, 1);
    }

    private MenuReorderRequest reorderRequest() {
        return new MenuReorderRequest(List.of(new MenuReorderRequest.Item(1L, 0L, 1)));
    }
}
