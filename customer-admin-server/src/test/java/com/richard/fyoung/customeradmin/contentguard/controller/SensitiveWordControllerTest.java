package com.richard.fyoung.customeradmin.contentguard.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.contentguard.dto.SensitiveWordSaveRequest;
import com.richard.fyoung.customeradmin.contentguard.service.SensitiveWordService;
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

/** 敏感词全局运行时写入的控制面门禁测试。 */
class SensitiveWordControllerTest {

    private SensitiveWordService service;
    private CrossTenantAuthority crossTenantAuthority;
    private SensitiveWordController controller;

    @BeforeEach
    void setUp() {
        service = mock(SensitiveWordService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new SensitiveWordController(service, crossTenantAuthority);
    }

    @Test
    void writeEndpoints_shouldRejectOrdinaryTenantBeforeMutation() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();
        SensitiveWordSaveRequest request = new SensitiveWordSaveRequest();

        assertForbidden(() -> controller.create(request));
        assertForbidden(() -> controller.update(1L, request));
        assertForbidden(() -> controller.toggle(1L, true));
        assertForbidden(() -> controller.delete(1L));
        assertForbidden(() -> controller.importWords(List.of("测试,OTHER,BLOCK")));

        verify(crossTenantAuthority, times(5)).requireCurrentUserAuthority();
        verifyNoInteractions(service);
    }

    @Test
    void writeEndpoints_shouldAllowControlPlaneUser() {
        SensitiveWordSaveRequest request = new SensitiveWordSaveRequest();
        List<String> lines = List.of("测试,OTHER,BLOCK");

        controller.create(request);
        controller.update(1L, request);
        controller.toggle(1L, true);
        controller.delete(1L);
        controller.importWords(lines);

        verify(crossTenantAuthority, times(5)).requireCurrentUserAuthority();
        verify(service).create(request);
        verify(service).update(1L, request);
        verify(service).toggle(1L, true);
        verify(service).delete(1L);
        verify(service).importWords(lines);
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }
}
