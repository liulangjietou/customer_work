package com.richard.fyoung.customeradmin.aiconfig.systemtool.controller;

import com.richard.fyoung.customeradmin.aiconfig.systemtool.dto.SystemToolSaveRequest;
import com.richard.fyoung.customeradmin.aiconfig.systemtool.service.SystemToolService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 系统级工具目录写入口的控制面门禁测试。 */
class SystemToolControllerTest {

    private SystemToolService systemToolService;
    private CrossTenantAuthority crossTenantAuthority;
    private SystemToolController controller;

    @BeforeEach
    void setUp() {
        systemToolService = mock(SystemToolService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new SystemToolController(systemToolService, crossTenantAuthority);
    }

    @Test
    void update_shouldRejectOrdinaryTenantBeforeChangingGlobalTool() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();

        BizException exception = assertThrows(BizException.class,
            () -> controller.update(1L, request()));

        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
        verifyNoInteractions(systemToolService);
    }

    @Test
    void update_shouldAllowControlPlaneUser() {
        SystemToolSaveRequest request = request();

        controller.update(1L, request);

        verify(crossTenantAuthority).requireCurrentUserAuthority();
        verify(systemToolService).update(1L, request);
    }

    private SystemToolSaveRequest request() {
        return new SystemToolSaveRequest("测试工具", "description", 1, null);
    }
}
