package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.CostForecastVO;
import com.richard.fyoung.customeradmin.billing.dto.TenantQuotaVO;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.quota.QuotaPeriod;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 金额消耗预测。预测与账单共用日归集表，避免另起一套成本口径。
 */
@Service
public class CostForecastService {

    private static final int MONEY_SCALE = 4;
    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final CwTenantUsageDailyMapper usageMapper;
    private final TenantQuotaService quotaService;

    public CostForecastService(CwTenantUsageDailyMapper usageMapper, TenantQuotaService quotaService) {
        this.usageMapper = usageMapper;
        this.quotaService = quotaService;
    }

    /** 查询指定租户、周期截至某日的金额预测；未配置金额预算时上限返回 0。 */
    public CostForecastVO forecast(String tenantId, QuotaPeriod period, LocalDate asOfDate) {
        requireTenant(tenantId);
        QuotaPeriod targetPeriod = period == null ? QuotaPeriod.MONTHLY : period;
        LocalDate targetDate = asOfDate == null ? LocalDate.now().minusDays(1) : asOfDate;
        validateDate(targetDate);
        List<TenantQuotaVO> quotas = quotaService.listByTenant(tenantId);
        BigDecimal amountLimit = quotas.stream()
            .filter(quota -> targetPeriod.name().equals(quota.getPeriod()))
            .filter(quota -> Boolean.TRUE.equals(quota.getEnabled()))
            .map(TenantQuotaVO::getAmountLimit)
            .filter(limit -> limit != null && limit.signum() > 0)
            .findFirst()
            .orElse(BigDecimal.ZERO);
        return forecast(tenantId, targetPeriod, targetDate, amountLimit);
    }

    /** 预算检测已取得配额时复用，避免再次读取客服端库。 */
    CostForecastVO forecast(TenantQuotaVO quota, LocalDate asOfDate) {
        QuotaPeriod period = QuotaPeriod.parse(quota.getPeriod());
        BigDecimal limit = quota.getAmountLimit() == null ? BigDecimal.ZERO : quota.getAmountLimit();
        return forecast(quota.getTenantId(), period, asOfDate, limit);
    }

    private CostForecastVO forecast(String tenantId,
                                    QuotaPeriod period,
                                    LocalDate asOfDate,
                                    BigDecimal amountLimit) {
        requireTenant(tenantId);
        validateDate(asOfDate);
        LocalDate periodStart = period.startOf(asOfDate);
        BigDecimal used = CrossTenantOperations.execute(
            () -> usageMapper.sumAmountByTenantAndRange(tenantId, periodStart, asOfDate));
        used = money(used);

        int elapsedDays = period == QuotaPeriod.DAILY ? 1 : asOfDate.getDayOfMonth();
        int totalDays = period == QuotaPeriod.DAILY ? 1 : asOfDate.lengthOfMonth();
        BigDecimal average = used.divide(BigDecimal.valueOf(elapsedDays), MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal forecast = average.multiply(BigDecimal.valueOf(totalDays))
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal normalizedLimit = money(amountLimit);

        CostForecastVO result = new CostForecastVO();
        result.setTenantId(tenantId);
        result.setPeriod(period.name());
        result.setPeriodKey(period.periodKey(asOfDate));
        result.setPeriodStart(periodStart);
        result.setAsOfDate(asOfDate);
        result.setElapsedDays(elapsedDays);
        result.setTotalDays(totalDays);
        result.setUsedAmount(used);
        result.setAverageDailyAmount(average);
        result.setForecastAmount(forecast);
        result.setAmountLimit(normalizedLimit);
        result.setUtilizationPercent(utilization(used, normalizedLimit));
        result.setForecastExceeded(normalizedLimit.signum() > 0 && forecast.compareTo(normalizedLimit) >= 0);
        return result;
    }

    private BigDecimal utilization(BigDecimal used, BigDecimal limit) {
        if (limit.signum() <= 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        }
        return used.multiply(ONE_HUNDRED).divide(limit, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void validateDate(LocalDate asOfDate) {
        if (asOfDate == null || asOfDate.isAfter(LocalDate.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "预测截止日不能晚于今天");
        }
    }

    private void requireTenant(String tenantId) {
        if (!TenantContext.isValidTenantId(tenantId)) {
            throw new BizException(ResultCode.PARAM_INVALID, "租户编码格式不合法");
        }
    }
}
