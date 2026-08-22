package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.BillingCsvFile;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingCsvExportServiceTest {

    private BillingReportService reportService;
    private BillingCsvExportService service;

    @BeforeEach
    void setUp() {
        reportService = mock(BillingReportService.class);
        service = new BillingCsvExportService(reportService);
    }

    @Test
    void exportTenant_shouldReturnUtf8BomRealRowsAndPreventSpreadsheetFormulaInjection() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        UsageAggregate row = usage("tenant-a", "open,ai", "=2+3", 3L, new BigDecimal("12.3400"));
        when(reportService.tenantBill("tenant-a", from, to)).thenReturn(List.of(row));

        BillingCsvFile file = service.exportTenant("tenant-a", from, to);
        String csv = new String(file.content(), StandardCharsets.UTF_8);

        assertEquals("billing-tenant-a-2026-08-01-2026-08-31.csv", file.filename());
        assertTrue(csv.startsWith("\uFEFFtenant_id,provider,model_name"));
        assertTrue(csv.contains("tenant-a,\"open,ai\",'=2+3,3,1,2,1,3,12.3400,CNY"));
        verify(reportService).tenantBill("tenant-a", from, to);
    }

    @Test
    void exportPlatform_shouldUsePlatformOverviewRows() {
        LocalDate date = LocalDate.of(2026, 8, 1);
        when(reportService.platformOverview(date, date)).thenReturn(List.of());

        BillingCsvFile file = service.exportPlatform(date, date);

        assertEquals("billing-platform-2026-08-01-2026-08-01.csv", file.filename());
        verify(reportService).platformOverview(date, date);
    }

    @Test
    void export_shouldRejectReversedRange() {
        BizException exception = assertThrows(BizException.class,
            () -> service.exportPlatform(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1)));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
    }

    private UsageAggregate usage(String tenantId,
                                 String provider,
                                 String model,
                                 long calls,
                                 BigDecimal amount) {
        UsageAggregate row = new UsageAggregate();
        row.setTenantId(tenantId);
        row.setProvider(provider);
        row.setModelName(model);
        row.setCallCount(calls);
        row.setInputTokens(1L);
        row.setOutputTokens(2L);
        row.setCachedTokens(1L);
        row.setTotalTokens(3L);
        row.setAmount(amount);
        return row;
    }
}
