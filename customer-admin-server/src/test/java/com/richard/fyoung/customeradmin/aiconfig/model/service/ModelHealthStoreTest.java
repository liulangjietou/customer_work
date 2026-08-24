package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthEventType;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthOverrideMode;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthEvent;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthSnapshotMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 健康快照、追加事件和人工覆盖的事务语义测试。 */
class ModelHealthStoreTest {

    private AiModelHealthSnapshotMapper snapshotMapper;
    private AiModelHealthEventMapper eventMapper;
    private ModelHealthStore store;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(AiModelHealthSnapshotMapper.class);
        eventMapper = mock(AiModelHealthEventMapper.class);
        ModelHealthMonitorProperties properties = new ModelHealthMonitorProperties();
        properties.setFailureThreshold(3);
        properties.setRecoveryThreshold(2);
        properties.setProbeIntervalSeconds(300);
        properties.setCooldownSeconds(60);
        store = new ModelHealthStore(snapshotMapper, eventMapper,
            new ModelHealthStateMachine(properties));
        when(eventMapper.insert(any(AiModelHealthEvent.class))).thenReturn(1);
    }

    @Test
    void firstAuthFailure_shouldCreateDegradedSnapshotAndAuditEvent() {
        when(snapshotMapper.lockSnapshot(11L, "tenant-a")).thenReturn(null);
        when(snapshotMapper.insertIgnore(any(AiModelHealthSnapshot.class))).thenReturn(1);
        LocalDateTime occurredAt = LocalDateTime.now();

        ModelHealthStore.RecordResult recorded = store.record(model(), failure(occurredAt,
            ModelHealthErrorCategory.AUTH), ModelProbeSource.MANUAL);

        assertEquals(ModelHealthStatus.DEGRADED.name(), recorded.snapshot().healthStatus());
        assertEquals("FAILED", recorded.snapshot().authStatus());
        assertEquals(1, recorded.snapshot().consecutiveFailures());
        assertTrue(recorded.snapshot().routingAvailable());
        assertTrue(recorded.applied());
        ArgumentCaptor<AiModelHealthEvent> event = ArgumentCaptor.forClass(AiModelHealthEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelHealthEventType.STATE_TRANSITION.name(), event.getValue().getEventType());
        assertEquals(ModelProbeSource.MANUAL.name(), event.getValue().getSource());
        assertEquals(ModelHealthErrorCategory.AUTH.name(), event.getValue().getErrorCategory());
    }

    @Test
    void thirdFailure_shouldMoveDeploymentToUnhealthyAndStartCooldown() {
        LocalDateTime occurredAt = LocalDateTime.now();
        AiModelHealthSnapshot current = current(ModelHealthStatus.DEGRADED, 2, 0);
        current.setLastProbeAt(occurredAt.minusSeconds(1));
        when(snapshotMapper.lockSnapshot(11L, "tenant-a")).thenReturn(current);
        when(snapshotMapper.updateById(current)).thenReturn(1);

        ModelHealthStore.RecordResult recorded = store.record(model(), failure(occurredAt,
            ModelHealthErrorCategory.RATE_LIMIT), ModelProbeSource.SCHEDULED);

        assertEquals(ModelHealthStatus.UNHEALTHY.name(), recorded.snapshot().healthStatus());
        assertEquals(3, recorded.snapshot().consecutiveFailures());
        assertEquals(occurredAt.plusSeconds(60), recorded.snapshot().cooldownUntil());
        assertFalse(recorded.snapshot().routingAvailable());
        assertTrue(recorded.routingChanged());
    }

    @Test
    void staleProbe_shouldAppendEventWithoutOverwritingSnapshot() {
        LocalDateTime latestAt = LocalDateTime.now();
        AiModelHealthSnapshot current = current(ModelHealthStatus.HEALTHY, 0, 2);
        current.setLastProbeAt(latestAt);
        when(snapshotMapper.lockSnapshot(11L, "tenant-a")).thenReturn(current);

        ModelHealthStore.RecordResult recorded = store.record(model(),
            failure(latestAt.minusSeconds(1), ModelHealthErrorCategory.TIMEOUT),
            ModelProbeSource.SCHEDULED);

        assertFalse(recorded.applied());
        assertEquals(ModelHealthStatus.HEALTHY.name(), recorded.snapshot().healthStatus());
        verify(snapshotMapper, never()).updateById(any(AiModelHealthSnapshot.class));
        ArgumentCaptor<AiModelHealthEvent> event = ArgumentCaptor.forClass(AiModelHealthEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelHealthEventType.STALE_PROBE.name(), event.getValue().getEventType());
    }

    @Test
    void forceHealthyOverride_shouldMakeUnderlyingUnhealthyDeploymentRouteable() {
        AiModelHealthSnapshot current = current(ModelHealthStatus.UNHEALTHY, 3, 0);
        when(snapshotMapper.lockSnapshot(11L, "tenant-a")).thenReturn(current);
        when(snapshotMapper.updateById(current)).thenReturn(1);
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);

        ModelHealthStore.RecordResult recorded = store.override(model(),
            new ModelHealthOverrideRequest(ModelHealthOverrideMode.FORCE_HEALTHY,
                "approved emergency recovery", expiresAt), 7L, "operator");

        assertEquals(ModelHealthStatus.UNHEALTHY.name(), recorded.snapshot().healthStatus());
        assertEquals(ModelHealthStatus.HEALTHY.name(), recorded.snapshot().effectiveHealthStatus());
        assertTrue(recorded.snapshot().routingAvailable());
        assertTrue(recorded.routingChanged());
        ArgumentCaptor<AiModelHealthEvent> event = ArgumentCaptor.forClass(AiModelHealthEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelHealthEventType.OVERRIDE_SET.name(), event.getValue().getEventType());
        assertEquals(7L, event.getValue().getOperatorId());
    }

    @Test
    void expiredOverride_shouldRestoreAutomaticRoutingExactlyOnce() {
        LocalDateTime now = LocalDateTime.now();
        AiModelHealthSnapshot current = current(ModelHealthStatus.UNHEALTHY, 3, 0);
        current.setOverrideMode(ModelHealthOverrideMode.FORCE_HEALTHY.name());
        current.setOverrideReason("temporary recovery");
        current.setOverrideUntil(now.minusSeconds(1));
        when(snapshotMapper.lockSnapshot(11L, "tenant-a")).thenReturn(current);
        when(snapshotMapper.updateById(current)).thenReturn(1);

        ModelHealthStore.RecordResult recorded = store.expireOverride(model(), now);

        assertTrue(recorded.applied());
        assertTrue(recorded.routingChanged());
        assertEquals(ModelHealthOverrideMode.AUTO.name(), recorded.snapshot().overrideMode());
        assertFalse(recorded.snapshot().routingAvailable());
        ArgumentCaptor<AiModelHealthEvent> event = ArgumentCaptor.forClass(AiModelHealthEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelHealthEventType.OVERRIDE_EXPIRED.name(), event.getValue().getEventType());
    }

    private ModelTestResult failure(LocalDateTime occurredAt, ModelHealthErrorCategory category) {
        return new ModelTestResult(ConnectivityTestStatus.FAILED, occurredAt,
            "probe failed", ModelHealthStatus.DEGRADED.name(), category.name(), 31L);
    }

    private AiModelConfig model() {
        AiModelConfig model = new AiModelConfig();
        model.setId(11L);
        model.setTenantId("tenant-a");
        return model;
    }

    private AiModelHealthSnapshot current(ModelHealthStatus status, int failures, int successes) {
        AiModelHealthSnapshot snapshot = new AiModelHealthSnapshot();
        snapshot.setModelConfigId(11L);
        snapshot.setTenantId("tenant-a");
        snapshot.setHealthStatus(status.name());
        snapshot.setAuthStatus("PASSED");
        snapshot.setCapabilityStatus("UNKNOWN");
        snapshot.setConsecutiveFailures(failures);
        snapshot.setConsecutiveSuccesses(successes);
        snapshot.setOverrideMode(ModelHealthOverrideMode.AUTO.name());
        snapshot.setRevision(7);
        return snapshot;
    }
}
