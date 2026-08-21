package com.richard.fyoung.customeradmin.billing.controller;

import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaSaveRequest;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.service.BillingReportService;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceAdminService;
import com.richard.fyoung.customeradmin.billing.service.TenantQuotaService;
import com.richard.fyoung.customeradmin.billing.service.UsageAggregationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link BillingController} 的跨租户控制面门禁测试。 */
class BillingControllerTest {

    private TenantQuotaService quotaService;
    private ModelPriceAdminService priceService;
    private BillingReportService reportService;
    private UsageAggregationService aggregationService;
    private CrossTenantAuthority crossTenantAuthority;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        quotaService = mock(TenantQuotaService.class);
        priceService = mock(ModelPriceAdminService.class);
        reportService = mock(BillingReportService.class);
        aggregationService = mock(UsageAggregationService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new BillingController(
            quotaService,
            priceService,
            reportService,
            aggregationService,
            crossTenantAuthority);
    }

    @Test
    void listQuota_shouldRejectOrdinaryDefaultUser() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);

        BizException exception = assertThrows(BizException.class,
            () -> controller.listQuota("acme"));

        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
        verify(quotaService, never()).listByTenant("acme");
    }

    @Test
    void tenantBill_shouldRejectExplicitCrossTenantReadWithoutControlPlaneRole() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        LocalDate date = LocalDate.of(2026, 8, 21);
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("default");

            BizException exception = assertThrows(BizException.class,
                () -> controller.tenantBill("acme", date, date));

            assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
            verify(reportService, never()).tenantBill("acme", date, date);
        }
    }

    @Test
    void tenantBill_shouldAllowCurrentTenantWithoutControlPlaneRole() {
        LocalDate date = LocalDate.of(2026, 8, 21);
        when(reportService.tenantBill("default", date, date)).thenReturn(List.of());
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("default");

            controller.tenantBill("default", date, date);

            verify(reportService).tenantBill("default", date, date);
        }
    }

    @Test
    void controlPlaneManagementEndpoints_shouldRejectOrdinaryTenantBeforeServiceCall() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        TenantQuotaSaveRequest quota = new TenantQuotaSaveRequest();
        AiModelPrice price = new AiModelPrice();
        LocalDate date = LocalDate.of(2026, 8, 21);

        assertForbidden(() -> controller.saveQuota(quota));
        assertForbidden(() -> controller.deleteQuota("tenant-a", "MONTHLY"));
        assertForbidden(controller::listPrice);
        assertForbidden(() -> controller.createPrice(price));
        assertForbidden(() -> controller.deletePrice(1L));
        assertForbidden(() -> controller.platformOverview(date, date));
        assertForbidden(() -> controller.aggregate(date));

        verifyNoInteractions(quotaService, priceService, reportService, aggregationService);
    }

    @Test
    void controlPlaneManagementEndpoints_shouldAllowControlPlaneUser() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        TenantQuotaSaveRequest quota = new TenantQuotaSaveRequest();
        AiModelPrice price = new AiModelPrice();
        LocalDate date = LocalDate.of(2026, 8, 21);

        controller.saveQuota(quota);
        controller.deleteQuota("tenant-a", "MONTHLY");
        controller.listPrice();
        controller.createPrice(price);
        controller.deletePrice(1L);
        controller.platformOverview(date, date);
        controller.aggregate(date);

        verify(quotaService).save(quota);
        verify(quotaService).delete("tenant-a", "MONTHLY");
        verify(priceService).list();
        verify(priceService).create(price);
        verify(priceService).delete(1L);
        verify(reportService).platformOverview(date, date);
        verify(aggregationService).aggregate(date);
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }
}
