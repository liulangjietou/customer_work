package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.dto.SloWindowEvaluation;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloCallAggregate;
import com.richard.fyoung.customeradmin.slo.mapper.SloCallAggregateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 基于真实调用日志进行同步短/长窗口 error-budget 评估。 */
@Service
public class SloEvaluationService {

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int SCALE = 6;
    private static final String STATUS_NO_DATA = "NO_DATA";
    private static final String STATUS_INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
    private static final String STATUS_BURNING = "BURNING";
    private static final String STATUS_HEALTHY = "HEALTHY";

    private final SloPolicyService policyService;
    private final SloCallAggregateMapper aggregateMapper;
    private final SloAlertMapper alertMapper;
    private final Clock clock;

    @Autowired
    public SloEvaluationService(SloPolicyService policyService,
                                SloCallAggregateMapper aggregateMapper,
                                SloAlertMapper alertMapper) {
        this(policyService, aggregateMapper, alertMapper, Clock.systemUTC());
    }

    SloEvaluationService(SloPolicyService policyService,
                         SloCallAggregateMapper aggregateMapper,
                         SloAlertMapper alertMapper,
                         Clock clock) {
        this.policyService = policyService;
        this.aggregateMapper = aggregateMapper;
        this.alertMapper = alertMapper;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public SloEvaluationVO evaluate(Long policyId) {
        String tenantId = SloPolicyService.requireTenant();
        SloPolicy policy = policyService.requirePolicy(policyId, tenantId);
        if (!Boolean.TRUE.equals(policy.getEnabled())) {
            throw new BizException(ResultCode.PARAM_INVALID, "SLO 策略未启用");
        }
        String agentCode = resolveAgentCode(policy, tenantId);
        Instant now = clock.instant();
        SloWindowEvaluation shortWindow = evaluateWindow(policy, tenantId, agentCode, now,
            policy.getShortWindowMinutes());
        SloWindowEvaluation longWindow = evaluateWindow(policy, tenantId, agentCode, now,
            policy.getLongWindowMinutes());
        int minimumSampleCount = resolveMinimumSampleCount(policy);
        boolean noData = shortWindow.total() == 0 && longWindow.total() == 0;
        boolean enoughSamples = shortWindow.total() >= minimumSampleCount
            && longWindow.total() >= minimumSampleCount;
        boolean burning = enoughSamples
            && shortWindow.burnRate().compareTo(policy.getBurnRateThreshold()) >= 0
            && longWindow.burnRate().compareTo(policy.getBurnRateThreshold()) >= 0;
        boolean alertCreated = burning && createAlert(policy, tenantId, now, shortWindow, longWindow);
        String status = noData ? STATUS_NO_DATA
            : !enoughSamples ? STATUS_INSUFFICIENT_DATA
            : burning ? STATUS_BURNING : STATUS_HEALTHY;
        return new SloEvaluationVO(policy.getId(), policy.getPolicyName(), policy.getScopeType(),
            policy.getScopeKey(), LocalDateTime.ofInstant(now, ZoneOffset.UTC), status,
            minimumSampleCount, shortWindow, longWindow, alertCreated);
    }

    private int resolveMinimumSampleCount(SloPolicy policy) {
        return policy.getMinimumSampleCount() == null
            ? SloPolicy.DEFAULT_MINIMUM_SAMPLE_COUNT : policy.getMinimumSampleCount();
    }

    private String resolveAgentCode(SloPolicy policy, String tenantId) {
        return switch (policy.getScopeType()) {
            case "TENANT" -> null;
            case "AGENT" -> policy.getScopeKey();
            case "CHANNEL" -> {
                String code = aggregateMapper.findAgentCodeByChannel(tenantId, policy.getScopeKey());
                if (code == null || code.isBlank()) {
                    throw new BizException(ResultCode.PARAM_INVALID, "渠道未绑定启用的智能体，无法评估 SLO");
                }
                yield code;
            }
            default -> throw new BizException(ResultCode.PARAM_INVALID, "不支持的 SLO 范围");
        };
    }

    private SloWindowEvaluation evaluateWindow(SloPolicy policy, String tenantId, String agentCode,
                                               Instant now, int minutes) {
        long toMs = now.toEpochMilli();
        long fromMs = now.minusSeconds(minutes * 60L).toEpochMilli();
        SloCallAggregate aggregate = aggregateMapper.aggregate(tenantId, agentCode, fromMs, toMs,
            policy.getLatencyThresholdMs());
        long total = value(aggregate == null ? null : aggregate.getTotal());
        long availabilityGood = value(aggregate == null ? null : aggregate.getAvailabilityGood());
        long latencyGood = value(aggregate == null ? null : aggregate.getLatencyGood());
        long compositeGood = value(aggregate == null ? null : aggregate.getCompositeGood());
        if (total == 0) {
            BigDecimal zero = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
            return new SloWindowEvaluation(minutes, 0, 0, 0, 0, 0,
                zero, zero, zero, zero);
        }
        long bad = total - compositeGood;
        BigDecimal availabilityRatio = ratio(availabilityGood, total);
        BigDecimal latencyRatio = ratio(latencyGood, total);
        BigDecimal availabilityRemaining = remaining(total, total - availabilityGood,
            policy.getAvailabilityTarget());
        BigDecimal latencyRemaining = remaining(total, total - latencyGood, policy.getLatencyTarget());
        BigDecimal remaining = availabilityRemaining.min(latencyRemaining);
        BigDecimal burn = burn(availabilityRatio, policy.getAvailabilityTarget())
            .max(burn(latencyRatio, policy.getLatencyTarget()));
        return new SloWindowEvaluation(minutes, total, compositeGood, bad, availabilityGood, latencyGood,
            availabilityRatio, latencyRatio, remaining, burn);
    }

    private boolean createAlert(SloPolicy policy, String tenantId, Instant now,
                                SloWindowEvaluation shortWindow, SloWindowEvaluation longWindow) {
        SloAlert alert = new SloAlert();
        alert.setTenantId(tenantId);
        alert.setPolicyId(policy.getId());
        alert.setWindowEndMinute(now.getEpochSecond() / 60L);
        alert.setAlertType("MULTI_WINDOW_BURN");
        alert.setShortBurnRate(shortWindow.burnRate());
        alert.setLongBurnRate(longWindow.burnRate());
        alert.setFirstSeenAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        return alertMapper.insertIgnore(alert) == 1;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE,
            RoundingMode.HALF_UP);
    }

    private static BigDecimal remaining(long total, long bad, BigDecimal target) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal allowed = BigDecimal.valueOf(total).multiply(ONE.subtract(target));
        return allowed.subtract(BigDecimal.valueOf(bad)).max(BigDecimal.ZERO)
            .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal burn(BigDecimal actualRatio, BigDecimal target) {
        return ONE.subtract(actualRatio).divide(ONE.subtract(target), SCALE, RoundingMode.HALF_UP);
    }
}
