package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEffectiveState;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEventType;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentMetricsAvailability;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentMetricsVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentEvent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelCertificationService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentServiceTest {

    private AiModelExperimentMapper experimentMapper;
    private AiModelExperimentEventMapper eventMapper;
    private AiAgentMapper agentMapper;
    private ModelConfigAccess modelConfigAccess;
    private ModelCertificationService certificationService;
    private ModelExperimentMetricsProvider metricsProvider;
    private ModelExperimentService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new Configuration(), ""), AiModelExperiment.class);
        experimentMapper = mock(AiModelExperimentMapper.class);
        eventMapper = mock(AiModelExperimentEventMapper.class);
        agentMapper = mock(AiAgentMapper.class);
        modelConfigAccess = mock(ModelConfigAccess.class);
        certificationService = mock(ModelCertificationService.class);
        metricsProvider = mock(ModelExperimentMetricsProvider.class);
        AdminTenantProperties properties = new AdminTenantProperties();
        properties.setEnabled(false);
        service = new ModelExperimentService(experimentMapper, eventMapper, agentMapper,
            modelConfigAccess, certificationService, metricsProvider, properties);
    }

    @Test
    void create_shouldCaptureImmutableRevisionSaltAndArmSnapshots_withoutStartingTraffic() {
        when(agentMapper.selectById(7L)).thenReturn(enabledAgent());
        when(modelConfigAccess.findVisibleAnyStateById(10L)).thenReturn(deployment(10L, "model-a", 3));
        when(modelConfigAccess.findVisibleAnyStateById(20L)).thenReturn(deployment(20L, "model-b", 5));
        doAnswer(invocation -> {
            AiModelExperiment inserted = invocation.getArgument(0);
            inserted.setId(99L);
            return 1;
        }).when(experimentMapper).insert(any(AiModelExperiment.class));

        ModelExperimentVO created = service.create(new ModelExperimentCreateRequest(
            "模型升级实验", 7L, 10L, 20L, 2500, 1000L,
            new BigDecimal("0.0500000"), 2500L, LocalDateTime.now().plusDays(2)));

        assertEquals(ModelExperimentStatus.DRAFT.name(), created.getStatus());
        assertEquals(1, created.getRevision());
        ArgumentCaptor<AiModelExperiment> inserted = ArgumentCaptor.forClass(AiModelExperiment.class);
        verify(experimentMapper).insert(inserted.capture());
        assertEquals(32, inserted.getValue().getAssignmentSalt().length());
        assertEquals("model-a", created.getControlModelRef());
        assertEquals(3, created.getControlEndpointRevision());
        assertEquals("model-b", created.getTreatmentModelRef());
        assertEquals(5, created.getTreatmentEndpointRevision());
        assertTrue(created.getExperimentCode().startsWith("exp-"));
        verify(certificationService, never()).requirePassedCurrent(any());
        verify(eventMapper, never()).insert(any(AiModelExperimentEvent.class));
    }

    @Test
    void start_shouldRevalidateActiveCertifiedArms_andAppendStartEvent() {
        AiModelExperiment experiment = draftExperiment();
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(agentMapper.selectById(7L)).thenReturn(enabledAgent());
        AiModelConfig control = deployment(10L, "model-a", 3);
        AiModelConfig treatment = deployment(20L, "model-b", 5);
        when(modelConfigAccess.findVisibleAnyStateById(10L)).thenReturn(control);
        when(modelConfigAccess.findVisibleAnyStateById(20L)).thenReturn(treatment);
        when(experimentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        ModelExperimentVO started = service.start(99L);

        assertEquals(ModelExperimentStatus.RUNNING.name(), started.getStatus());
        verify(certificationService).requirePassedCurrent(control);
        verify(certificationService).requirePassedCurrent(treatment);
        ArgumentCaptor<AiModelExperimentEvent> event = ArgumentCaptor.forClass(AiModelExperimentEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelExperimentEventType.START.name(), event.getValue().getEventType());
        assertEquals(ModelExperimentStatus.DRAFT.name(), event.getValue().getFromStatus());
        assertEquals(ModelExperimentStatus.RUNNING.name(), event.getValue().getToStatus());
    }

    @Test
    void start_shouldRejectDeploymentDrift_beforeCertification() {
        AiModelExperiment experiment = draftExperiment();
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(agentMapper.selectById(7L)).thenReturn(enabledAgent());
        when(modelConfigAccess.findVisibleAnyStateById(10L)).thenReturn(deployment(10L, "model-a", 4));

        BizException error = assertThrows(BizException.class, () -> service.start(99L));

        assertTrue(error.getMessage().contains("配置已漂移"));
        verify(certificationService, never()).requirePassedCurrent(any());
        verify(experimentMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void start_withoutReliablePublishTask_shouldThrowSoTransactionCanRollback() {
        AiModelExperiment experiment = draftExperiment();
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        RuntimePublishTaskMapper taskMapper = mock(RuntimePublishTaskMapper.class);
        service = governedService(publisher, taskMapper);
        stubStartValidation(experiment);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(publisher.publishExperiment(7L, 99L, ModelExperimentPublishAction.ACTIVATE))
            .thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.start(99L));

        assertEquals(ResultCode.RUNTIME_PUBLISH_FAILED, error.getResultCode());
        verify(experimentMapper, times(1)).update(any(), any(LambdaUpdateWrapper.class));
        verify(eventMapper, never()).insert(any(AiModelExperimentEvent.class));
    }

    @Test
    void start_shouldAssociateActivationTaskAndExposeActivatingState() {
        AiModelExperiment experiment = draftExperiment();
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        RuntimePublishTaskMapper taskMapper = mock(RuntimePublishTaskMapper.class);
        service = governedService(publisher, taskMapper);
        stubStartValidation(experiment);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(publisher.publishExperiment(7L, 99L, ModelExperimentPublishAction.ACTIVATE))
            .thenReturn("activate-1");
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(publishTask(
            "activate-1", ModelExperimentPublishAction.ACTIVATE, RuntimePublishStatus.PENDING)));

        ModelExperimentVO started = service.start(99L);

        assertEquals("activate-1", started.getActivationTaskId());
        assertEquals(ModelExperimentEffectiveState.ACTIVATING.name(), started.getEffectiveState());
        assertEquals("activate-1", started.getEffectiveTaskId());
        verify(publisher).publishExperiment(7L, 99L, ModelExperimentPublishAction.ACTIVATE);
        verify(experimentMapper, times(2)).update(any(), any(LambdaUpdateWrapper.class));
        verify(eventMapper).insert(any(AiModelExperimentEvent.class));
    }

    @Test
    void stop_shouldRequireReason_andAppendStopEvent() {
        AiModelExperiment running = draftExperiment();
        running.setStatus(ModelExperimentStatus.RUNNING.name());
        when(experimentMapper.selectById(99L)).thenReturn(running);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThrows(BizException.class, () -> service.stop(99L, " "));
        ModelExperimentVO stopped = service.stop(99L, "P95 持续升高");

        assertEquals(ModelExperimentStatus.STOPPED.name(), stopped.getStatus());
        assertEquals("P95 持续升高", stopped.getStopReason());
        ArgumentCaptor<AiModelExperimentEvent> event = ArgumentCaptor.forClass(AiModelExperimentEvent.class);
        verify(eventMapper).insert(event.capture());
        assertEquals(ModelExperimentEventType.STOP.name(), event.getValue().getEventType());
        assertEquals("P95 持续升高", event.getValue().getReason());
    }

    @Test
    void stop_shouldAssociateDeactivationTaskAndExposeItsRealPendingState() {
        AiModelExperiment running = draftExperiment();
        running.setStatus(ModelExperimentStatus.RUNNING.name());
        running.setStartedAt(LocalDateTime.now().minusMinutes(5));
        running.setActivationTaskId("activate-1");
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        RuntimePublishTaskMapper taskMapper = mock(RuntimePublishTaskMapper.class);
        service = governedService(publisher, taskMapper);
        when(experimentMapper.selectById(99L)).thenReturn(running);
        when(experimentMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(publisher.publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE))
            .thenReturn("deactivate-1");
        RuntimePublishTask deactivation = publishTask(
            "deactivate-1", ModelExperimentPublishAction.DEACTIVATE, RuntimePublishStatus.PENDING);
        when(taskMapper.selectBatchIds(any())).thenReturn(java.util.List.of(deactivation));

        ModelExperimentVO stopped = service.stop(99L, "主动结束灰度");

        assertEquals("deactivate-1", stopped.getDeactivationTaskId());
        assertEquals(ModelExperimentEffectiveState.DEACTIVATING.name(), stopped.getEffectiveState());
        assertEquals(RuntimePublishStatus.PENDING.name(), stopped.getEffectiveTaskStatus());
        assertEquals(EvalGateStatus.PENDING.name(), stopped.getEffectiveTaskGateStatus());
        verify(publisher).publishExperiment(7L, 99L, ModelExperimentPublishAction.DEACTIVATE);
        verify(taskMapper).selectBatchIds(any());
        verify(experimentMapper, times(2)).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void list_shouldBatchLoadReferencedPublishTasks() {
        CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
        RuntimePublishTaskMapper taskMapper = mock(RuntimePublishTaskMapper.class);
        service = governedService(publisher, taskMapper);
        AiModelExperiment first = draftExperiment();
        first.setStatus(ModelExperimentStatus.RUNNING.name());
        first.setActivationTaskId("activate-1");
        AiModelExperiment second = draftExperiment();
        second.setId(100L);
        second.setStatus(ModelExperimentStatus.RUNNING.name());
        second.setActivationTaskId("activate-2");
        when(experimentMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(first, second));
        RuntimePublishTask firstTask = publishTask(
            "activate-1", 99L, ModelExperimentPublishAction.ACTIVATE, RuntimePublishStatus.APPLIED);
        RuntimePublishTask secondTask = publishTask(
            "activate-2", 100L, ModelExperimentPublishAction.ACTIVATE, RuntimePublishStatus.PENDING);
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(firstTask, secondTask));

        List<ModelExperimentVO> result = service.list(null, null);

        assertEquals(ModelExperimentEffectiveState.ACTIVE.name(), result.get(0).getEffectiveState());
        assertEquals(ModelExperimentEffectiveState.ACTIVATING.name(), result.get(1).getEffectiveState());
        verify(taskMapper, times(1)).selectBatchIds(any());
    }

    @Test
    void metrics_shouldExposeAwaitingRuntime_withoutInventingZeroMetrics() {
        AiModelExperiment experiment = draftExperiment();
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(metricsProvider.snapshot(experiment)).thenReturn(ModelExperimentMetricsSnapshot.awaitingRuntime());

        ModelExperimentMetricsVO metrics = service.metrics(99L);

        assertEquals(ModelExperimentMetricsAvailability.AWAITING_RUNTIME.name(), metrics.availability());
        assertNull(metrics.samples());
        assertNull(metrics.errorRate());
        assertNull(metrics.p95LatencyMs());
        assertNull(metrics.evaluatedAt());
        assertTrue(metrics.message().contains("尚未写入"));
    }

    private AiAgent enabledAgent() {
        AiAgent agent = new AiAgent();
        agent.setId(7L);
        agent.setStatus(1);
        return agent;
    }

    private ModelExperimentService governedService(CustomerWorkConfigPublisher publisher,
                                                    RuntimePublishTaskMapper taskMapper) {
        AdminTenantProperties properties = new AdminTenantProperties();
        properties.setEnabled(false);
        return new ModelExperimentService(experimentMapper, eventMapper, agentMapper,
            modelConfigAccess, certificationService, metricsProvider, properties, publisher, taskMapper);
    }

    private void stubStartValidation(AiModelExperiment experiment) {
        when(experimentMapper.selectById(99L)).thenReturn(experiment);
        when(agentMapper.selectById(7L)).thenReturn(enabledAgent());
        when(modelConfigAccess.findVisibleAnyStateById(10L))
            .thenReturn(deployment(10L, "model-a", 3));
        when(modelConfigAccess.findVisibleAnyStateById(20L))
            .thenReturn(deployment(20L, "model-b", 5));
        when(experimentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    private RuntimePublishTask publishTask(String id,
                                           ModelExperimentPublishAction action,
                                           RuntimePublishStatus status) {
        return publishTask(id, 99L, action, status);
    }

    private RuntimePublishTask publishTask(String id,
                                           Long experimentId,
                                           ModelExperimentPublishAction action,
                                           RuntimePublishStatus status) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(id);
        task.setTenantId("default");
        task.setExperimentId(experimentId);
        task.setExperimentPublishAction(action.name());
        task.setStatus(status.name());
        task.setGateStatus(EvalGateStatus.PENDING.name());
        return task;
    }

    private AiModelConfig deployment(Long id, String modelRef, int revision) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setModel(modelRef);
        model.setEndpointRevision(revision);
        model.setStatus(1);
        model.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        return model;
    }

    private AiModelExperiment draftExperiment() {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(99L);
        experiment.setTenantId("default");
        experiment.setExperimentCode("exp-test");
        experiment.setExperimentName("test");
        experiment.setAgentId(7L);
        experiment.setControlDeploymentId(10L);
        experiment.setControlModelRef("model-a");
        experiment.setControlEndpointRevision(3);
        experiment.setTreatmentDeploymentId(20L);
        experiment.setTreatmentModelRef("model-b");
        experiment.setTreatmentEndpointRevision(5);
        experiment.setRevision(1);
        experiment.setAssignmentSalt("0123456789abcdef0123456789abcdef");
        experiment.setTreatmentBps(2500);
        experiment.setStatus(ModelExperimentStatus.DRAFT.name());
        experiment.setMinSample(1000L);
        experiment.setMaxErrorRate(new BigDecimal("0.0500000"));
        experiment.setMaxP95LatencyMs(2500L);
        experiment.setExpiresAt(LocalDateTime.now().plusDays(1));
        return experiment;
    }
}
