package com.richard.fyoung.customeradmin.subjectquota.controller;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.subjectquota.dto.SubjectQuotaLevelSaveRequest;
import com.richard.fyoung.customeradmin.subjectquota.service.SubjectQuotaAdminService;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** default 共享配额基线与普通租户私有档位的写权限边界。 */
class SubjectQuotaControllerTest {

    @Test
    void defaultCaseAliasOrdinaryUser_shouldNotUpdateSharedLevelBaseline() {
        SubjectQuotaAdminService service = mock(SubjectQuotaAdminService.class);
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        SubjectQuotaController controller = new SubjectQuotaController(service, authority);
        SubjectQuotaLevelSaveRequest request = new SubjectQuotaLevelSaveRequest();
        doThrow(new BizException(ResultCode.TENANT_VIEW_FORBIDDEN))
            .when(authority).requireCurrentUserAuthority();

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("DEFAULT");

            assertThrows(BizException.class, () -> controller.saveLevel(request));

            verify(service, never()).saveLevel("DEFAULT", request);
        }
    }

    @Test
    void defaultControlPlaneUser_shouldUpdateSharedLevelBaseline() {
        SubjectQuotaAdminService service = mock(SubjectQuotaAdminService.class);
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        SubjectQuotaController controller = new SubjectQuotaController(service, authority);

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn(TenantContext.DEFAULT);

            controller.deleteLevel("standard");

            verify(authority).requireCurrentUserAuthority();
            verify(service).deleteLevel(TenantContext.DEFAULT, "standard");
        }
    }

    @Test
    void businessTenantOrdinaryUser_shouldUpdateOwnPrivateLevel() {
        SubjectQuotaAdminService service = mock(SubjectQuotaAdminService.class);
        CrossTenantAuthority authority = mock(CrossTenantAuthority.class);
        SubjectQuotaController controller = new SubjectQuotaController(service, authority);
        SubjectQuotaLevelSaveRequest request = new SubjectQuotaLevelSaveRequest();

        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");

            controller.saveLevel(request);

            verifyNoInteractions(authority);
            verify(service).saveLevel("tenant-a", request);
        }
    }
}
