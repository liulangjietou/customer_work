package com.richard.fyoung.customeradmin.system.loginimage.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageEnabledRequest;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageReorderRequest;
import com.richard.fyoung.customeradmin.system.loginimage.service.LoginCarouselImageService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 登录轮播图全局写入口的控制面门禁测试。 */
class LoginImageAdminControllerTest {

    private LoginCarouselImageService imageService;
    private CrossTenantAuthority crossTenantAuthority;
    private LoginImageAdminController controller;

    @BeforeEach
    void setUp() {
        imageService = mock(LoginCarouselImageService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new LoginImageAdminController(imageService, crossTenantAuthority);
    }

    @Test
    void writeEndpoints_shouldRejectOrdinaryTenant() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();

        assertForbidden(() -> controller.upload(mock(MultipartFile.class)));
        assertForbidden(() -> controller.updateEnabled(1L, new LoginImageEnabledRequest(true)));
        assertForbidden(() -> controller.reorder(new LoginImageReorderRequest(List.of(1L))));
        assertForbidden(() -> controller.delete(1L));
        verifyNoInteractions(imageService);
    }

    @Test
    void writeEndpoints_shouldAllowControlPlaneUser() {
        MultipartFile file = mock(MultipartFile.class);
        LoginImageEnabledRequest enabledRequest = new LoginImageEnabledRequest(true);
        LoginImageReorderRequest reorderRequest = new LoginImageReorderRequest(List.of(1L));

        controller.upload(file);
        controller.updateEnabled(1L, enabledRequest);
        controller.reorder(reorderRequest);
        controller.delete(1L);

        verify(imageService).upload(file);
        verify(imageService).updateEnabled(1L, true);
        verify(imageService).reorder(reorderRequest);
        verify(imageService).delete(1L);
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }
}
