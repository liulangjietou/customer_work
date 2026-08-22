package com.richard.fyoung.customeradmin.billing.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.billing.dto.BillingCsvFile;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaSaveRequest;
import com.richard.fyoung.customeradmin.billing.entity.AiModelPrice;
import com.richard.fyoung.customeradmin.billing.service.BillingCsvExportService;
import com.richard.fyoung.customeradmin.billing.service.BillingReportService;
import com.richard.fyoung.customeradmin.billing.service.CostAlertService;
import com.richard.fyoung.customeradmin.billing.service.CostForecastService;
import com.richard.fyoung.customeradmin.billing.service.ModelPriceAdminService;
import com.richard.fyoung.customeradmin.billing.service.TenantQuotaService;
import com.richard.fyoung.customeradmin.billing.service.UsageAggregationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import com.richard.fyoung.customerwork.safety.quota.QuotaPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
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
    private CostAlertService alertService;
    private CostForecastService forecastService;
    private BillingCsvExportService csvExportService;
    private CrossTenantAuthority crossTenantAuthority;
    private BillingController controller;

    @BeforeEach
    void setUp() {
        quotaService = mock(TenantQuotaService.class);
        priceService = mock(ModelPriceAdminService.class);
        reportService = mock(BillingReportService.class);
        aggregationService = mock(UsageAggregationService.class);
        alertService = mock(CostAlertService.class);
        forecastService = mock(CostForecastService.class);
        csvExportService = mock(BillingCsvExportService.class);
        crossTenantAuthority = mock(CrossTenantAuthority.class);
        controller = new BillingController(
            quotaService,
            priceService,
            reportService,
            aggregationService,
            alertService,
            forecastService,
            csvExportService,
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

    @Test
    void forecast_shouldRejectExplicitCrossTenantReadBeforeServiceCall() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");

            BizException exception = assertThrows(BizException.class,
                () -> controller.forecast("tenant-b", QuotaPeriod.MONTHLY, LocalDate.of(2026, 8, 20)));

            assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
            verifyNoInteractions(forecastService);
        }
    }

    @Test
    void listAlerts_shouldScopeOrdinaryUserToEffectiveTenant() {
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        when(alertService.list("tenant-a", "OPEN", 100)).thenReturn(List.of());
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");

            controller.listAlerts(null, "OPEN", 100);

            verify(alertService).list("tenant-a", "OPEN", 100);
        }
    }

    @Test
    void acknowledgeAlert_shouldBeTenantScopedAndPassCurrentUser() {
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class);
             MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            controller.acknowledgeAlert(9L, "tenant-a");

            verify(alertService).acknowledge(9L, "tenant-a", 7L);
        }
    }

    @Test
    void export_shouldKeepOrdinaryTenantOnOwnDetailedBill() {
        LocalDate date = LocalDate.of(2026, 8, 20);
        byte[] content = "csv".getBytes(StandardCharsets.UTF_8);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(false);
        when(csvExportService.exportTenant("tenant-a", date, date))
            .thenReturn(new BillingCsvFile("billing-tenant-a.csv", content));
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::effectiveTenant).thenReturn("tenant-a");

            ResponseEntity<byte[]> response = controller.export(null, date, date);

            assertEquals(200, response.getStatusCode().value());
            assertEquals("attachment; filename=\"billing-tenant-a.csv\"",
                response.getHeaders().getFirst("Content-Disposition"));
            assertEquals(content, response.getBody());
            verify(csvExportService).exportTenant("tenant-a", date, date);
            verify(csvExportService, never()).exportPlatform(date, date);
        }
    }

    @Test
    void export_shouldUsePlatformOverviewOnlyForControlPlaneWithoutTenant() {
        LocalDate date = LocalDate.of(2026, 8, 20);
        when(crossTenantAuthority.hasCurrentUserAuthority()).thenReturn(true);
        when(csvExportService.exportPlatform(date, date))
            .thenReturn(new BillingCsvFile("billing-platform.csv", new byte[0]));

        controller.export(null, date, date);

        verify(csvExportService).exportPlatform(date, date);
        verify(csvExportService, never()).exportTenant(org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private void assertForbidden(Runnable action) {
        BizException exception = assertThrows(BizException.class, action::run);
        assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
    }
}
