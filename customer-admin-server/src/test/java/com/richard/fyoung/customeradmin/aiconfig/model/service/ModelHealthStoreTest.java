package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 健康状态机、错误分类投影和事件一致性测试。 */
class ModelHealthStoreTest {

    private AiModelHealthSnapshotMapper snapshotMapper;
    private AiModelHealthEventMapper eventMapper;
    private ModelHealthStore store;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(AiModelHealthSnapshotMapper.class);
        eventMapper = mock(AiModelHealthEventMapper.class);
        store = new ModelHealthStore(snapshotMapper, eventMapper);
    }

    @Test
    void firstAuthFailure_shouldCreateDegradedSnapshotAndAuditEvent() {
        when(snapshotMapper.insertIgnore(any(AiModelHealthSnapshot.class))).thenReturn(1);
        when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        LocalDateTime occurredAt = LocalDateTime.now();
        ModelTestResult result = new ModelTestResult(ConnectivityTestStatus.FAILED, occurredAt,
            "模型凭据不可用", ModelHealthStatus.DEGRADED.name(),
            ModelHealthErrorCategory.AUTH.name(), 31L);

        ModelHealthStore.RecordResult recorded = store.record(model(), result, ModelProbeSource.MANUAL);
        ModelHealthSnapshotVO snapshot = recorded.snapshot();

        assertEquals(ModelHealthStatus.DEGRADED.name(), snapshot.healthStatus());
        assertEquals("FAILED", snapshot.authStatus());
        assertEquals(1, snapshot.consecutiveFailures());
        assertNotNull(snapshot.nextProbeAt());
        assertTrue(recorded.applied());
        ArgumentCaptor<AiModelHealthEvent> event = ArgumentCaptor.forClass(AiModelHealthEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals("tenant-a", event.getValue().getTenantId());
        assertEquals(ModelProbeSource.MANUAL.name(), event.getValue().getSource());
        assertEquals(ModelHealthErrorCategory.AUTH.name(), event.getValue().getErrorCategory());
    }

    @Test
    void thirdFailure_shouldMoveDeploymentToUnhealthy() {
        AiModelHealthSnapshot updated = current(ModelHealthStatus.UNHEALTHY.name(), 3);
        updated.setLastErrorCategory(ModelHealthErrorCategory.RATE_LIMIT.name());
        updated.setRevision(8);
        when(snapshotMapper.updateIfNewer(any(), eq(false), eq(false), eq(3))).thenReturn(1);
        when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(updated);
        ModelTestResult failure = new ModelTestResult(ConnectivityTestStatus.FAILED, LocalDateTime.now(),
            "quota exhausted", ModelHealthStatus.DEGRADED.name(),
            ModelHealthErrorCategory.RATE_LIMIT.name(), 10L);

        ModelHealthSnapshotVO snapshot = store.record(model(), failure, ModelProbeSource.SCHEDULED).snapshot();

        assertEquals(ModelHealthStatus.UNHEALTHY.name(), snapshot.healthStatus());
        assertEquals(3, snapshot.consecutiveFailures());
        assertEquals(ModelHealthErrorCategory.RATE_LIMIT.name(), snapshot.lastErrorCategory());
        assertEquals(8, snapshot.revision());
    }

    @Test
    void firstSuccessAfterUnhealthy_shouldEnterRecoveringAndResetFailures() {
        AiModelHealthSnapshot updated = current(ModelHealthStatus.RECOVERING.name(), 0);
        updated.setAuthStatus("PASSED");
        updated.setLastSuccessAt(LocalDateTime.now());
        updated.setRevision(8);
        when(snapshotMapper.updateIfNewer(any(), eq(true), eq(false), eq(3))).thenReturn(1);
        when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(updated);
        ModelTestResult success = new ModelTestResult(ConnectivityTestStatus.SUCCESS, LocalDateTime.now(),
            null, ModelHealthStatus.HEALTHY.name(), null, 8L);

        ModelHealthSnapshotVO snapshot = store.record(model(), success, ModelProbeSource.SCHEDULED).snapshot();

        assertEquals(ModelHealthStatus.RECOVERING.name(), snapshot.healthStatus());
        assertEquals("PASSED", snapshot.authStatus());
        assertEquals(0, snapshot.consecutiveFailures());
        assertNotNull(snapshot.lastSuccessAt());
    }

    @Test
    void olderProbe_shouldRemainInEventHistoryWithoutOverwritingSnapshot() {
        LocalDateTime latestAt = LocalDateTime.now();
        AiModelHealthSnapshot latest = current(ModelHealthStatus.HEALTHY.name(), 0);
        latest.setLastProbeAt(latestAt);
        when(snapshotMapper.updateIfNewer(any(), eq(false), eq(false), eq(3))).thenReturn(0);
        when(snapshotMapper.selectOne(any(QueryWrapper.class))).thenReturn(latest);
        ModelTestResult older = new ModelTestResult(ConnectivityTestStatus.FAILED,
            latestAt.minusSeconds(1), "timeout", ModelHealthStatus.DEGRADED.name(),
            ModelHealthErrorCategory.TIMEOUT.name(), 1000L);

        ModelHealthStore.RecordResult recorded = store.record(model(), older, ModelProbeSource.SCHEDULED);

        assertFalse(recorded.applied());
        assertEquals(ModelHealthStatus.HEALTHY.name(), recorded.snapshot().healthStatus());
        assertEquals(0, recorded.snapshot().consecutiveFailures());
        verify(snapshotMapper).updateIfNewer(any(), eq(false), eq(false), eq(3));
    }

    private AiModelConfig model() {
        AiModelConfig model = new AiModelConfig();
        model.setId(11L);
        model.setTenantId("tenant-a");
        return model;
    }

    private AiModelHealthSnapshot current(String status, Integer failures) {
        AiModelHealthSnapshot snapshot = new AiModelHealthSnapshot();
        snapshot.setModelConfigId(11L);
        snapshot.setTenantId("tenant-a");
        snapshot.setHealthStatus(status);
        snapshot.setAuthStatus("PASSED");
        snapshot.setCapabilityStatus("UNKNOWN");
        snapshot.setConsecutiveFailures(failures);
        snapshot.setRevision(7);
        return snapshot;
    }
}
