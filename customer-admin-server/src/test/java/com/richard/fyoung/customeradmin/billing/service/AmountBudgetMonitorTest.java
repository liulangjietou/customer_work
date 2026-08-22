package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.CostForecastVO;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customeradmin.billing.entity.CostAlert;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmountBudgetMonitorTest {

    private TenantQuotaService quotaService;
    private CostForecastService forecastService;
    private CostAlertService alertService;
    private AmountBudgetMonitor monitor;

    @BeforeEach
    void setUp() {
        quotaService = mock(TenantQuotaService.class);
        forecastService = mock(CostForecastService.class);
        alertService = mock(CostAlertService.class);
        monitor = new AmountBudgetMonitor(quotaService, forecastService, alertService);
        when(alertService.createIfAbsent(any())).thenReturn(true);
    }

    @Test
    void evaluate_shouldCreateWarningAndMonthlyForecastAlert() {
        LocalDate statDate = LocalDate.of(2026, 8, 10);
        TenantQuotaVO quota = quota("tenant-a", "MONTHLY", "100", 80, true);
        when(quotaService.listByTenant("tenant-a")).thenReturn(List.of(quota));
        when(forecastService.forecast(quota, statDate))
            .thenReturn(forecast("tenant-a", "MONTHLY", "2026-08", "85", "100", "150", true));

        monitor.evaluate(statDate, Set.of("tenant-a"));

        ArgumentCaptor<CostAlert> captor = ArgumentCaptor.forClass(CostAlert.class);
        verify(alertService, org.mockito.Mockito.times(2)).createIfAbsent(captor.capture());
        assertEquals(Set.of(CostAlertType.BUDGET_WARNING.name(), CostAlertType.FORECAST_EXCEEDED.name()),
            captor.getAllValues().stream().map(CostAlert::getAlertType).collect(java.util.stream.Collectors.toSet()));
        captor.getAllValues().forEach(alert -> {
            assertEquals("tenant-a", alert.getTenantId());
            assertEquals("2026-08", alert.getPeriodKey());
            assertNotNull(alert.getFirstSeenAt());
        });
    }

    @Test
    void evaluate_shouldCreateOnlyExceededAlertAfterLimitReached() {
        LocalDate statDate = LocalDate.of(2026, 8, 10);
        TenantQuotaVO quota = quota("tenant-a", "MONTHLY", "100", 80, true);
        when(quotaService.listByTenant("tenant-a")).thenReturn(List.of(quota));
        when(forecastService.forecast(quota, statDate))
            .thenReturn(forecast("tenant-a", "MONTHLY", "2026-08", "100", "100", "310", true));

        monitor.evaluate(statDate, Set.of("tenant-a"));

        ArgumentCaptor<CostAlert> captor = ArgumentCaptor.forClass(CostAlert.class);
        verify(alertService).createIfAbsent(captor.capture());
        assertEquals(CostAlertType.BUDGET_EXCEEDED.name(), captor.getValue().getAlertType());
    }

    @Test
    void evaluate_shouldIgnoreDisabledOrUnlimitedAmountQuota() {
        when(quotaService.listByTenant("tenant-a")).thenReturn(List.of(
            quota("tenant-a", "DAILY", "0", 80, true),
            quota("tenant-a", "MONTHLY", "100", 80, false)));

        monitor.evaluate(LocalDate.of(2026, 8, 10), Set.of("tenant-a"));

        verify(forecastService, never()).forecast(any(TenantQuotaVO.class), any(LocalDate.class));
        verify(alertService, never()).createIfAbsent(any());
    }

    @Test
    void evaluate_shouldAlwaysOpenIndependentTransactionAfterCommit() throws Exception {
        Method method = AmountBudgetMonitor.class.getMethod("evaluate", LocalDate.class, Set.class);
        Transactional annotation = method.getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation());
    }

    private TenantQuotaVO quota(String tenantId, String period, String limit, int warnPercent, boolean enabled) {
        TenantQuotaVO quota = new TenantQuotaVO();
        quota.setTenantId(tenantId);
        quota.setPeriod(period);
        quota.setAmountLimit(new BigDecimal(limit));
        quota.setWarnPercent(warnPercent);
        quota.setEnabled(enabled);
        return quota;
    }

    private CostForecastVO forecast(String tenantId,
                                    String period,
                                    String periodKey,
                                    String used,
                                    String limit,
                                    String forecast,
                                    boolean forecastExceeded) {
        CostForecastVO result = new CostForecastVO();
        result.setTenantId(tenantId);
        result.setPeriod(period);
        result.setPeriodKey(periodKey);
        result.setUsedAmount(new BigDecimal(used));
        result.setAmountLimit(new BigDecimal(limit));
        result.setForecastAmount(new BigDecimal(forecast));
        result.setForecastExceeded(forecastExceeded);
        return result;
    }
}
