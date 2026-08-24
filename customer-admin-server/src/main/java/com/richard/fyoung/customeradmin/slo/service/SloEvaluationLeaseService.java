package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SLO 周期评估的数据库租约；Pod 宕机后由租约过期自动接管。 */
@Service
public class SloEvaluationLeaseService {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_BACKOFF_SHIFT = 8;
    private static final long MIN_LEASE_MS = 1000L;

    private final SloPolicyMapper policyMapper;
    private final SloAutomationProperties properties;
    private final Clock clock;
    private final String workerId;

    @Autowired
    public SloEvaluationLeaseService(SloPolicyMapper policyMapper,
                                     SloAutomationProperties properties) {
        this(policyMapper, properties, Clock.systemUTC(),
            ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID());
    }

    SloEvaluationLeaseService(SloPolicyMapper policyMapper,
                              SloAutomationProperties properties,
                              Clock clock,
                              String workerId) {
        this.policyMapper = policyMapper;
        this.properties = properties;
        this.clock = clock;
        this.workerId = workerId;
    }

    public List<SloPolicy> claimDue() {
        long now = clock.millis();
        int limit = Math.max(1, Math.min(properties.getEvaluationBatchSize(), 200));
        long leaseMs = Math.max(MIN_LEASE_MS, properties.getEvaluationLeaseMs());
        List<SloPolicy> candidates = CrossTenantOperations.execute(
            () -> policyMapper.findDueCandidates(now, limit));
        List<SloPolicy> claimed = new ArrayList<>();
        for (SloPolicy candidate : candidates) {
            int changed = CrossTenantOperations.execute(() -> policyMapper.claimEvaluation(
                candidate.getId(), workerId, now, now + leaseMs));
            if (changed == 1) {
                candidate.setEvaluationLeaseOwner(workerId);
                candidate.setEvaluationLeaseUntilMs(now + leaseMs);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    public void complete(SloPolicy policy, SloEvaluationVO result) {
        long now = clock.millis();
        LocalDateTime evaluatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        CrossTenantOperations.execute(() -> policyMapper.markEvaluationSuccess(
            policy.getId(), workerId, now + Math.max(1000L, properties.getEvaluationIntervalMs()),
            result.status(), evaluatedAt));
    }

    public void fail(SloPolicy policy, Throwable failure) {
        long now = clock.millis();
        int failures = policy.getEvaluationFailures() == null ? 1 : policy.getEvaluationFailures() + 1;
        long base = Math.max(1000L, properties.getScanIntervalMs());
        long shifted = base * (1L << Math.min(failures, MAX_BACKOFF_SHIFT));
        long max = Math.max(base, properties.getEvaluationIntervalMs());
        String error = errorMessage(failure);
        LocalDateTime evaluatedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        CrossTenantOperations.execute(() -> policyMapper.markEvaluationFailure(
            policy.getId(), workerId, now + Math.min(shifted, max), error, evaluatedAt));
    }

    private String errorMessage(Throwable failure) {
        String message = failure.getMessage() == null
            ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
