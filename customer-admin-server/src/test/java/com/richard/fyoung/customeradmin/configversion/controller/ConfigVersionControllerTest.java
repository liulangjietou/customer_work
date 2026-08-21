package com.richard.fyoung.customeradmin.configversion.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionPageQuery;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.dto.GrayReleaseRequest;
import com.richard.fyoung.customeradmin.configversion.service.ConfigRollbackService;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
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

/** 全量回滚与跨租户灰度发布的控制面门禁测试。 */
class ConfigVersionControllerTest {

    private ConfigVersionService versionService;
    private ConfigRollbackService rollbackService;
    private CrossTenantAuthority crossTenantAuthority;
    private ConfigVersionController controller;

    @BeforeEach
    void setUp() {
        versionService = mock(ConfigVersionService.class);
        rollbackService = mock(ConfigRollbackService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new ConfigVersionController(versionService, rollbackService, crossTenantAuthority);
    }

    @Test
    void controlPlaneEndpoints_shouldRejectOrdinaryTenant() {
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(crossTenantAuthority).requireCurrentUserAuthority();
        GrayReleaseRequest request = grayRequest();
        ConfigVersionPageQuery query = new ConfigVersionPageQuery();

        assertForbidden(() -> controller.page(query));
        assertForbidden(() -> controller.detail(1L));
        assertForbidden(() -> controller.listByTarget("AGENT", "agent-a"));
        assertForbidden(() -> controller.rollback(1L, "rollback"));
        assertForbidden(() -> controller.grayRelease(1L, request));

        verifyNoInteractions(versionService);
        verifyNoInteractions(rollbackService);
    }

    @Test
    void controlPlaneEndpoints_shouldAllowControlPlaneUser() {
        GrayReleaseRequest request = grayRequest();
        ConfigVersionPageQuery query = new ConfigVersionPageQuery();

        controller.page(query);
        controller.detail(1L);
        controller.listByTarget("AGENT", "agent-a");
        controller.rollback(1L, "rollback");
        controller.grayRelease(1L, request);

        verify(crossTenantAuthority, times(5)).requireCurrentUserAuthority();
        verify(versionService).page(query);
        verify(versionService).detail(1L);
        verify(versionService).listByTarget(ConfigType.AGENT, "agent-a");
        verify(rollbackService).rollback(1L, "rollback");
        verify(rollbackService).grayRelease(1L, request.getTenantCodes(), request.getRemark());
    }

    private GrayReleaseRequest grayRequest() {
        GrayReleaseRequest request = new GrayReleaseRequest();
        request.setTenantCodes(List.of("tenant-a"));
        request.setRemark("gray");
        return request;
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }
}
