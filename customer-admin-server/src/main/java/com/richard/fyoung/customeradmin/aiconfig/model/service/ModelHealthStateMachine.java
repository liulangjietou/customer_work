package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthOverrideMode;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 模型健康状态机。数据库事务负责串行化同一部署，本类只表达连续失败、冷却和恢复阈值语义。
 */
@Component
public class ModelHealthStateMachine {

    private final ModelHealthMonitorProperties properties;

    public ModelHealthStateMachine(ModelHealthMonitorProperties properties) {
        this.properties = properties;
    }

    public AiModelHealthSnapshot initial(AiModelConfig model, ModelTestResult result,
                                         LocalDateTime occurredAt) {
        AiModelHealthSnapshot snapshot = unknown(model, occurredAt);
        applyProbe(snapshot, result, occurredAt);
        return snapshot;
    }

    public AiModelHealthSnapshot unknown(AiModelConfig model, LocalDateTime now) {
        AiModelHealthSnapshot snapshot = new AiModelHealthSnapshot();
        snapshot.setModelConfigId(model.getId());
        snapshot.setTenantId(model.getTenantId());
        snapshot.setHealthStatus(ModelHealthStatus.UNKNOWN.name());
        snapshot.setAuthStatus("UNKNOWN");
        snapshot.setCapabilityStatus("UNKNOWN");
        snapshot.setConsecutiveFailures(0);
        snapshot.setConsecutiveSuccesses(0);
        snapshot.setNextProbeAt(now);
        snapshot.setOverrideMode(ModelHealthOverrideMode.AUTO.name());
        snapshot.setRevision(0);
        return snapshot;
    }

    public void applyProbe(AiModelHealthSnapshot snapshot, ModelTestResult result,
                           LocalDateTime occurredAt) {
        boolean success = result.testStatus() == ConnectivityTestStatus.SUCCESS;
        ModelHealthStatus previous = status(snapshot.getHealthStatus());
        int failures = value(snapshot.getConsecutiveFailures());
        int successes = value(snapshot.getConsecutiveSuccesses());

        if (success) {
            boolean recovering = previous == ModelHealthStatus.UNHEALTHY
                || previous == ModelHealthStatus.RECOVERING;
            successes = Math.min(recoveryThreshold(), Math.max(1, successes + 1));
            snapshot.setConsecutiveFailures(0);
            snapshot.setConsecutiveSuccesses(successes);
            snapshot.setHealthStatus(recovering && successes < recoveryThreshold()
                ? ModelHealthStatus.RECOVERING.name() : ModelHealthStatus.HEALTHY.name());
            snapshot.setAuthStatus("PASSED");
            snapshot.setLastErrorCategory(null);
            snapshot.setLastSuccessAt(occurredAt);
            if (ModelHealthStatus.HEALTHY.name().equals(snapshot.getHealthStatus())) {
                snapshot.setCooldownUntil(null);
            }
            snapshot.setNextProbeAt(occurredAt.plusSeconds(probeIntervalSeconds()));
        } else {
            failures++;
            snapshot.setConsecutiveFailures(failures);
            snapshot.setConsecutiveSuccesses(0);
            boolean unhealthy = previous == ModelHealthStatus.UNHEALTHY
                || previous == ModelHealthStatus.RECOVERING
                || failures >= failureThreshold();
            snapshot.setHealthStatus(unhealthy
                ? ModelHealthStatus.UNHEALTHY.name() : ModelHealthStatus.DEGRADED.name());
            if (ModelHealthErrorCategory.AUTH.name().equals(category(result))) {
                snapshot.setAuthStatus("FAILED");
            }
            snapshot.setLastErrorCategory(category(result));
            snapshot.setLastFailureAt(occurredAt);
            if (unhealthy) {
                LocalDateTime cooldownUntil = occurredAt.plusSeconds(cooldownSeconds());
                snapshot.setCooldownUntil(cooldownUntil);
                snapshot.setNextProbeAt(cooldownUntil);
            } else {
                snapshot.setNextProbeAt(occurredAt.plusSeconds(probeIntervalSeconds()));
            }
        }

        snapshot.setLastLatencyMs(result.latencyMs());
        snapshot.setLastMessage(result.message());
        snapshot.setLastProbeAt(occurredAt);
        snapshot.setRevision(value(snapshot.getRevision()) + 1);
    }

    public Overlay overlay(AiModelHealthSnapshot snapshot, LocalDateTime now) {
        if (snapshot == null) {
            return new Overlay(ModelHealthStatus.UNKNOWN.name(), ModelHealthStatus.UNKNOWN.name(),
                true, ModelHealthOverrideMode.AUTO.name(), null, 0);
        }
        ModelHealthOverrideMode mode = activeOverride(snapshot, now);
        String effective = switch (mode) {
            case FORCE_HEALTHY -> ModelHealthStatus.HEALTHY.name();
            case FORCE_UNHEALTHY -> ModelHealthStatus.UNHEALTHY.name();
            case AUTO -> status(snapshot.getHealthStatus()).name();
        };
        boolean available = !ModelHealthStatus.UNHEALTHY.name().equals(effective)
            && !ModelHealthStatus.RECOVERING.name().equals(effective);
        return new Overlay(status(snapshot.getHealthStatus()).name(), effective, available,
            mode.name(), mode == ModelHealthOverrideMode.AUTO ? null : snapshot.getOverrideUntil(),
            value(snapshot.getRevision()));
    }

    public boolean hasExpiredOverride(AiModelHealthSnapshot snapshot, LocalDateTime now) {
        return configuredOverride(snapshot) != ModelHealthOverrideMode.AUTO
            && snapshot.getOverrideUntil() != null
            && !snapshot.getOverrideUntil().isAfter(now);
    }

    private ModelHealthOverrideMode activeOverride(AiModelHealthSnapshot snapshot, LocalDateTime now) {
        ModelHealthOverrideMode configured = configuredOverride(snapshot);
        if (configured == ModelHealthOverrideMode.AUTO || snapshot.getOverrideUntil() == null
            || !snapshot.getOverrideUntil().isAfter(now)) {
            return ModelHealthOverrideMode.AUTO;
        }
        return configured;
    }

    private ModelHealthOverrideMode configuredOverride(AiModelHealthSnapshot snapshot) {
        try {
            return snapshot == null || snapshot.getOverrideMode() == null
                ? ModelHealthOverrideMode.AUTO
                : ModelHealthOverrideMode.valueOf(snapshot.getOverrideMode());
        } catch (IllegalArgumentException e) {
            return ModelHealthOverrideMode.AUTO;
        }
    }

    private ModelHealthStatus status(String raw) {
        try {
            return raw == null ? ModelHealthStatus.UNKNOWN : ModelHealthStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ModelHealthStatus.UNKNOWN;
        }
    }

    private String category(ModelTestResult result) {
        return result.errorCategory() == null
            ? ModelHealthErrorCategory.UNKNOWN.name() : result.errorCategory();
    }

    private int failureThreshold() {
        return Math.max(1, properties.getFailureThreshold());
    }

    private int recoveryThreshold() {
        return Math.max(1, properties.getRecoveryThreshold());
    }

    private long probeIntervalSeconds() {
        return Math.max(1L, properties.getProbeIntervalSeconds());
    }

    private long cooldownSeconds() {
        return Math.max(1L, properties.getCooldownSeconds());
    }

    private int value(Integer raw) {
        return raw == null ? 0 : raw;
    }

    /** 发布载荷只关心会改变运行时选择语义的投影。 */
    public record Overlay(String healthStatus,
                          String effectiveHealthStatus,
                          boolean routingAvailable,
                          String overrideMode,
                          LocalDateTime overrideUntil,
                          int revision) {

        public boolean routingEquivalent(Overlay other) {
            return other != null
                && Objects.equals(effectiveHealthStatus, other.effectiveHealthStatus)
                && routingAvailable == other.routingAvailable
                && Objects.equals(overrideMode, other.overrideMode)
                && Objects.equals(overrideUntil, other.overrideUntil);
        }
    }
}
