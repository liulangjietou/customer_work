package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEventType;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentMetricsAvailability;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentEvent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentLifecycleServiceTest {

    private AiModelExperimentMapper experimentMapper;
    private AiModelExperimentEventMapper eventMapper;
    private ModelExperimentMetricsProvider metricsProvider;
    private ModelExperimentLifecycleService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new Configuration(), ""), AiModelExperiment.class);
        experimentMapper = mock(AiModelExperimentMapper.class);
        eventMapper = mock(AiModelExperimentEventMapper.class);
        metricsProvider = mock(ModelExperimentMetricsProvider.class);
        service = new ModelExperimentLifecycleService(experimentMapper, eventMapper, metricsProvider);
    }

    @Test
    void expiredDraft_shouldCompleteAndAppendExpiredEvent_withoutReadingMetrics() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.DRAFT);
        experiment.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.reconcile(99L);

        ArgumentCaptor<AiModelExperimentEvent> event = ArgumentCaptor.forClass(AiModelExperimentEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelExperimentEventType.EXPIRED.name(), event.getValue().getEventType());
        assertEquals(ModelExperimentStatus.DRAFT.name(), event.getValue().getFromStatus());
        assertEquals(ModelExperimentStatus.COMPLETED.name(), event.getValue().getToStatus());
        verify(metricsProvider, never()).snapshot(any());
    }

    @Test
    void guardrail_shouldUseTreatmentMetricsAndAppendAutoStopEvent() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(metricsProvider.snapshot(experiment)).thenReturn(new ModelExperimentMetricsSnapshot(
            ModelExperimentMetricsAvailability.READY, "ready", 1000L,
            new BigDecimal("0.0100000"), 800L,
            new ModelExperimentMetricsSnapshot.Arm(900L, new BigDecimal("0.0100000"), 800L),
            new ModelExperimentMetricsSnapshot.Arm(100L, new BigDecimal("0.0800000"), 1600L),
            LocalDateTime.now()));
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.reconcile(99L);

        ArgumentCaptor<AiModelExperimentEvent> event = ArgumentCaptor.forClass(AiModelExperimentEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelExperimentEventType.AUTO_STOP.name(), event.getValue().getEventType());
        assertTrue(event.getValue().getReason().contains("实验组错误率护栏"));
    }

    @Test
    void concurrentManualStop_shouldNotAppendDuplicateAutomaticEvent() {
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        experiment.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        service.reconcile(99L);

        verify(eventMapper, never()).insert(any(AiModelExperimentEvent.class));
    }

    @Test
    void expiredRunning_shouldPersistDeactivationTaskBeforeAppendingEvent() {
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        service = new ModelExperimentLifecycleService(
            experimentMapper, eventMapper, metricsProvider, publisher);
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        experiment.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(publisher.publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE))
            .thenReturn("deactivate-expired");

        service.reconcile(99L);

        assertEquals("deactivate-expired", experiment.getDeactivationTaskId());
        verify(publisher).publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE);
        verify(experimentMapper, times(2)).update(any(), any(LambdaUpdateWrapper.class));
        verify(eventMapper).insert(any(AiModelExperimentEvent.class));
    }

    @Test
    void guardrailAutoStop_shouldPersistDeactivationTask() {
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        service = new ModelExperimentLifecycleService(
            experimentMapper, eventMapper, metricsProvider, publisher);
        AiModelExperiment experiment = experiment(ModelExperimentStatus.RUNNING);
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(metricsProvider.snapshot(experiment)).thenReturn(new ModelExperimentMetricsSnapshot(
            ModelExperimentMetricsAvailability.READY, "ready", 100L,
            new BigDecimal("0.1000000"), 100L, null,
            new ModelExperimentMetricsSnapshot.Arm(100L, new BigDecimal("0.1000000"), 100L),
            LocalDateTime.now()));
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(publisher.publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE))
            .thenReturn("deactivate-guardrail");

        service.reconcile(99L);

        assertEquals("deactivate-guardrail", experiment.getDeactivationTaskId());
        verify(publisher).publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE);
        verify(experimentMapper, times(2)).update(any(), any(LambdaUpdateWrapper.class));
    }

    private AiModelExperiment experiment(ModelExperimentStatus status) {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(99L);
        experiment.setTenantId("default");
        experiment.setAgentId(7L);
        experiment.setStatus(status.name());
        experiment.setMinSample(100L);
        experiment.setMaxErrorRate(new BigDecimal("0.0500000"));
        experiment.setMaxP95LatencyMs(1500L);
        experiment.setExpiresAt(LocalDateTime.now().plusDays(1));
        return experiment;
    }
}
