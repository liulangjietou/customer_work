package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthOverrideMode;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 连续失败、冷却和连续恢复阈值的纯状态机测试。 */
class ModelHealthStateMachineTest {

    private ModelHealthStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        ModelHealthMonitorProperties properties = new ModelHealthMonitorProperties();
        properties.setFailureThreshold(3);
        properties.setRecoveryThreshold(2);
        properties.setProbeIntervalSeconds(300);
        properties.setCooldownSeconds(60);
        stateMachine = new ModelHealthStateMachine(properties);
    }

    @Test
    void threeFailuresAndTwoSuccesses_shouldCompleteOneRecoveryCycle() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);
        AiModelHealthSnapshot snapshot = stateMachine.unknown(model(), now);

        stateMachine.applyProbe(snapshot, failure(now), now);
        assertEquals(ModelHealthStatus.DEGRADED.name(), snapshot.getHealthStatus());
        assertTrue(stateMachine.overlay(snapshot, now).routingAvailable());
        stateMachine.applyProbe(snapshot, failure(now.plusSeconds(1)), now.plusSeconds(1));
        stateMachine.applyProbe(snapshot, failure(now.plusSeconds(2)), now.plusSeconds(2));
        assertEquals(ModelHealthStatus.UNHEALTHY.name(), snapshot.getHealthStatus());
        assertEquals(now.plusSeconds(62), snapshot.getCooldownUntil());
        assertFalse(stateMachine.overlay(snapshot, now.plusSeconds(2)).routingAvailable());

        stateMachine.applyProbe(snapshot, success(now.plusSeconds(63)), now.plusSeconds(63));
        assertEquals(ModelHealthStatus.RECOVERING.name(), snapshot.getHealthStatus());
        assertEquals(1, snapshot.getConsecutiveSuccesses());
        assertFalse(stateMachine.overlay(snapshot, now.plusSeconds(63)).routingAvailable());
        stateMachine.applyProbe(snapshot, success(now.plusSeconds(64)), now.plusSeconds(64));
        assertEquals(ModelHealthStatus.HEALTHY.name(), snapshot.getHealthStatus());
        assertEquals(2, snapshot.getConsecutiveSuccesses());
        assertNull(snapshot.getCooldownUntil());
        assertTrue(stateMachine.overlay(snapshot, now.plusSeconds(64)).routingAvailable());
    }

    @Test
    void failureDuringRecovery_shouldReturnToUnhealthyAndRestartCooldown() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);
        AiModelHealthSnapshot snapshot = stateMachine.unknown(model(), now);
        snapshot.setHealthStatus(ModelHealthStatus.RECOVERING.name());
        snapshot.setConsecutiveSuccesses(1);

        stateMachine.applyProbe(snapshot, failure(now), now);

        assertEquals(ModelHealthStatus.UNHEALTHY.name(), snapshot.getHealthStatus());
        assertEquals(0, snapshot.getConsecutiveSuccesses());
        assertEquals(now.plusSeconds(60), snapshot.getCooldownUntil());
    }

    @Test
    void expiredManualOverride_shouldFallBackToUnderlyingState() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);
        AiModelHealthSnapshot snapshot = stateMachine.unknown(model(), now);
        snapshot.setHealthStatus(ModelHealthStatus.UNHEALTHY.name());
        snapshot.setOverrideMode(ModelHealthOverrideMode.FORCE_HEALTHY.name());
        snapshot.setOverrideUntil(now.plusMinutes(1));

        assertTrue(stateMachine.overlay(snapshot, now).routingAvailable());
        assertEquals(ModelHealthStatus.HEALTHY.name(),
            stateMachine.overlay(snapshot, now).effectiveHealthStatus());
        assertFalse(stateMachine.overlay(snapshot, now.plusMinutes(1)).routingAvailable());
        assertEquals(ModelHealthOverrideMode.AUTO.name(),
            stateMachine.overlay(snapshot, now.plusMinutes(1)).overrideMode());
    }

    private AiModelConfig model() {
        AiModelConfig model = new AiModelConfig();
        model.setId(11L);
        model.setTenantId("tenant-a");
        return model;
    }

    private ModelTestResult failure(LocalDateTime time) {
        return new ModelTestResult(ConnectivityTestStatus.FAILED, time, "timeout",
            ModelHealthStatus.DEGRADED.name(), ModelHealthErrorCategory.TIMEOUT.name(), 100L);
    }

    private ModelTestResult success(LocalDateTime time) {
        return new ModelTestResult(ConnectivityTestStatus.SUCCESS, time, null,
            ModelHealthStatus.HEALTHY.name(), null, 10L);
    }
}
