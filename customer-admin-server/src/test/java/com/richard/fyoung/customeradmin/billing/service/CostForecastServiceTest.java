package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.CostForecastVO;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.quota.QuotaPeriod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostForecastServiceTest {

    private CwTenantUsageDailyMapper usageMapper;
    private TenantQuotaService quotaService;
    private CostForecastService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(CwTenantUsageDailyMapper.class);
        quotaService = mock(TenantQuotaService.class);
        service = new CostForecastService(usageMapper, quotaService);
    }

    @Test
    void forecast_shouldUseNaturalMonthAndConfiguredAmountLimit() {
        LocalDate asOf = LocalDate.of(2026, 8, 10);
        when(quotaService.listByTenant("tenant-a")).thenReturn(List.of(quota("tenant-a", "MONTHLY", "500")));
        when(usageMapper.sumAmountByTenantAndRange("tenant-a", LocalDate.of(2026, 8, 1), asOf))
            .thenReturn(new BigDecimal("200"));

        CostForecastVO result = service.forecast("tenant-a", QuotaPeriod.MONTHLY, asOf);

        assertEquals("2026-08", result.getPeriodKey());
        assertEquals(10, result.getElapsedDays());
        assertEquals(31, result.getTotalDays());
        assertEquals(new BigDecimal("200.0000"), result.getUsedAmount());
        assertEquals(new BigDecimal("20.0000"), result.getAverageDailyAmount());
        assertEquals(new BigDecimal("620.0000"), result.getForecastAmount());
        assertEquals(new BigDecimal("500.0000"), result.getAmountLimit());
        assertEquals(new BigDecimal("40.00"), result.getUtilizationPercent());
        assertTrue(result.isForecastExceeded());
        verify(usageMapper).sumAmountByTenantAndRange("tenant-a", LocalDate.of(2026, 8, 1), asOf);
    }

    @Test
    void forecast_shouldTreatDailyForecastAsSettledActualAndNoBudgetAsUnlimited() {
        LocalDate asOf = LocalDate.of(2026, 8, 10);
        when(quotaService.listByTenant("tenant-a")).thenReturn(List.of());
        when(usageMapper.sumAmountByTenantAndRange("tenant-a", asOf, asOf))
            .thenReturn(new BigDecimal("12.34567"));

        CostForecastVO result = service.forecast("tenant-a", QuotaPeriod.DAILY, asOf);

        assertEquals(new BigDecimal("12.3457"), result.getUsedAmount());
        assertEquals(result.getUsedAmount(), result.getForecastAmount());
        assertEquals(new BigDecimal("0.00"), result.getUtilizationPercent());
        assertFalse(result.isForecastExceeded());
    }

    @Test
    void forecast_shouldRejectFutureDate() {
        BizException exception = assertThrows(BizException.class,
            () -> service.forecast("tenant-a", QuotaPeriod.MONTHLY, LocalDate.now().plusDays(1)));

        assertEquals(ResultCode.PARAM_INVALID, exception.getResultCode());
    }

    private TenantQuotaVO quota(String tenantId, String period, String limit) {
        TenantQuotaVO quota = new TenantQuotaVO();
        quota.setTenantId(tenantId);
        quota.setPeriod(period);
        quota.setAmountLimit(new BigDecimal(limit));
        quota.setEnabled(true);
        return quota;
    }
}
