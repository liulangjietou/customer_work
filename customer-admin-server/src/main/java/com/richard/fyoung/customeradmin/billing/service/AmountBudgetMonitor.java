package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.CostForecastVO;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customeradmin.billing.entity.CostAlert;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertStatus;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertType;
import com.richard.fyoung.customerwork.safety.quota.QuotaPeriod;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/** 归集提交后的金额预算检测。 */
@Service
public class AmountBudgetMonitor {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final TenantQuotaService quotaService;
    private final CostForecastService forecastService;
    private final CostAlertService alertService;

    public AmountBudgetMonitor(TenantQuotaService quotaService,
                               CostForecastService forecastService,
                               CostAlertService alertService) {
        this.quotaService = quotaService;
        this.forecastService = forecastService;
        this.alertService = alertService;
    }

    /** AFTER_COMMIT 回调已经离开原事务，必须开启新事务才能可靠提交告警。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void evaluate(LocalDate statDate, Set<String> tenantIds) {
        if (statDate == null || tenantIds == null || tenantIds.isEmpty()) {
            return;
        }
        for (String tenantId : tenantIds) {
            quotaService.listByTenant(tenantId).stream()
                .filter(this::hasEnabledAmountLimit)
                .forEach(quota -> evaluateQuota(quota, statDate));
        }
    }

    private void evaluateQuota(TenantQuotaVO quota, LocalDate statDate) {
        CostForecastVO forecast = forecastService.forecast(quota, statDate);
        BigDecimal used = forecast.getUsedAmount();
        BigDecimal limit = forecast.getAmountLimit();
        if (used.compareTo(limit) >= 0) {
            alertService.createIfAbsent(alert(quota, forecast, CostAlertType.BUDGET_EXCEEDED));
            return;
        }
        if (reachedWarning(quota, used, limit)) {
            alertService.createIfAbsent(alert(quota, forecast, CostAlertType.BUDGET_WARNING));
        }
        if (QuotaPeriod.MONTHLY.name().equals(quota.getPeriod()) && forecast.isForecastExceeded()) {
            alertService.createIfAbsent(alert(quota, forecast, CostAlertType.FORECAST_EXCEEDED));
        }
    }

    private CostAlert alert(TenantQuotaVO quota, CostForecastVO forecast, CostAlertType type) {
        LocalDateTime now = LocalDateTime.now();
        CostAlert alert = new CostAlert();
        alert.setTenantId(quota.getTenantId());
        alert.setPeriod(forecast.getPeriod());
        alert.setPeriodKey(forecast.getPeriodKey());
        alert.setAlertType(type.name());
        alert.setUsedAmount(forecast.getUsedAmount());
        alert.setLimitAmount(forecast.getAmountLimit());
        alert.setForecastAmount(forecast.getForecastAmount());
        alert.setStatus(CostAlertStatus.OPEN.name());
        alert.setFirstSeenAt(now);
        alert.setCreateTime(now);
        alert.setUpdateTime(now);
        return alert;
    }

    private boolean hasEnabledAmountLimit(TenantQuotaVO quota) {
        return quota != null
            && Boolean.TRUE.equals(quota.getEnabled())
            && quota.getTenantId() != null
            && quota.getAmountLimit() != null
            && quota.getAmountLimit().signum() > 0;
    }

    private boolean reachedWarning(TenantQuotaVO quota, BigDecimal used, BigDecimal limit) {
        Integer warnPercent = quota.getWarnPercent();
        if (warnPercent == null || warnPercent <= 0) {
            return false;
        }
        BigDecimal threshold = limit.multiply(BigDecimal.valueOf(warnPercent))
            .divide(ONE_HUNDRED, 4, RoundingMode.HALF_UP);
        return used.compareTo(threshold) >= 0;
    }
}
