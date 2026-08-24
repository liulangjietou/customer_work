package com.richard.fyoung.customeradmin.configversion.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.configversion.dto.ConfigVersionPageQuery;
import com.richard.fyoung.customeradmin.configversion.entity.ConfigType;
import com.richard.fyoung.customeradmin.configversion.dto.GrayReleaseRequest;
import com.richard.fyoung.customeradmin.configversion.service.ConfigVersionService;
import com.richard.fyoung.customeradmin.governance.change.service.GovernedChangeService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
    private GovernedChangeService governedChangeService;
    private CrossTenantAuthority crossTenantAuthority;
    private ConfigVersionController controller;

    @BeforeEach
    void setUp() {
        versionService = mock(ConfigVersionService.class);
        governedChangeService = mock(GovernedChangeService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new ConfigVersionController(versionService, governedChangeService, crossTenantAuthority);
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
        verifyNoInteractions(governedChangeService);
    }

    @Test
    void controlPlaneEndpoints_shouldAllowControlPlaneUser() {
        GrayReleaseRequest request = grayRequest();
        ConfigVersionPageQuery query = new ConfigVersionPageQuery();

        try (MockedStatic<StpUtil> stp = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            SaSession session = mock(SaSession.class);
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(9L);
            stp.when(StpUtil::getTokenSession).thenReturn(session);
            org.mockito.Mockito.when(session.getString("username")).thenReturn("checker");

            controller.page(query);
            controller.detail(1L);
            controller.listByTarget("AGENT", "agent-a");
            controller.rollback(1L, "rollback");
            controller.grayRelease(1L, request);
        }

        verify(crossTenantAuthority, times(5)).requireCurrentUserAuthority();
        verify(versionService).page(query);
        verify(versionService).detail(1L);
        verify(versionService).listByTarget(ConfigType.AGENT, "agent-a");
        verify(governedChangeService).submitRollback(1L, "rollback", 9L, "checker");
        verify(governedChangeService).submitGrayRelease(
            1L, request.getTenantCodes(), request.getRemark(), 9L, "checker");
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
