package com.richard.fyoung.customeradmin.improvement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.badcase.config.BadcaseGatewayProvider;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.eval.service.EvalAdminService;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementSignalGatewayProvider;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementCaseStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementEffectStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementReevaluationStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSignalGateway;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customeradmin.improvement.mapper.ImprovementSignalMapper;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImprovementCaseServiceTest {

    private AgentImprovementCaseMapper caseMapper;
    private ImprovementSignalMapper signalMapper;
    private CustomerWorkConfigPublisher publisher;
    private EvalAdminService evalAdminService;
    private RuntimePublishTaskService publishTaskService;
    private RuntimePublishTaskMapper publishTaskMapper;
    private ImprovementAutomationProperties properties;
    private ObjectMapper objectMapper;
    private ImprovementCaseService service;

    @BeforeEach
    void setUp() {
        caseMapper = mock(AgentImprovementCaseMapper.class);
        signalMapper = mock(ImprovementSignalMapper.class);
        publisher = mock(CustomerWorkConfigPublisher.class);
        evalAdminService = mock(EvalAdminService.class);
        publishTaskService = mock(RuntimePublishTaskService.class);
        publishTaskMapper = mock(RuntimePublishTaskMapper.class);
        properties = new ImprovementAutomationProperties();
        properties.setScanIntervalMs(1000L);
        properties.setObservationWindowMs(1000L);
        properties.setMinExposureCalls(20);
        properties.setMaxRecurrenceSignals(0);
        objectMapper = new ObjectMapper();

        ImprovementSignalGatewayProvider gatewayProvider = mock(ImprovementSignalGatewayProvider.class);
        when(gatewayProvider.get()).thenReturn(
            new ImprovementSignalGateway(signalMapper, mock(EvalCaseStore.class)));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ImprovementCaseService(caseMapper, gatewayProvider,
            mock(BadcaseGatewayProvider.class), mock(AiAgentMapper.class), publisher,
            evalAdminService, publishTaskService, publishTaskMapper, properties, objectMapper,
            transactionManager);
    }

    @Test
    void reevaluate_shouldFreezeExactCandidateAndTargetRegressionCase() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_FOR_REEVALUATION, candidate);
        when(caseMapper.lockById(1L)).thenReturn(row);
        when(evalAdminService.trigger(EvalType.INTENT, "fix refund")).thenReturn(
            comparison(completeBinding("model-v1"), List.of(), List.of("case-old")));

        service.reevaluate(1L, "fix refund");

        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH.name(), row.getStatus());
        assertEquals(ImprovementReevaluationStatus.PASSED.name(), row.getReevaluationStatus());
        assertEquals("run-current", row.getEvalRunId());
    }

    @Test
    void reevaluate_shouldRejectMismatchedCandidateAndStillFailingTargetCase() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_FOR_REEVALUATION, candidate);
        when(caseMapper.lockById(1L)).thenReturn(row);
        when(evalAdminService.trigger(EvalType.INTENT, null)).thenReturn(
            comparison(completeBinding("model-v2"), List.of("case-target"), List.of()));

        service.reevaluate(1L, null);

        assertEquals(ImprovementCaseStatus.REEVALUATION_FAILED.name(), row.getStatus());
        assertEquals(ImprovementReevaluationStatus.FAILED.name(), row.getReevaluationStatus());
        assertTrue(row.getReevaluationError().contains("制品版本与候选不一致"));
        assertTrue(row.getReevaluationError().contains("目标回归用例仍失败"));
    }

    @Test
    void publish_shouldRejectCandidateDriftBeforeEnqueue() throws Exception {
        EvalVersionBinding evaluated = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, evaluated);
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L)).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate("model-v2"));

        assertThrows(BizException.class, () -> service.publish(1L));

        verify(publishTaskService, never()).enqueueAgent(any());
        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH.name(), row.getStatus());
    }

    @Test
    void publishAndAutomation_shouldReachVerifiedOnlyAfterAppliedRevisionExposure() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, candidate);
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L)).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate);
        when(publishTaskService.enqueueAgent(7L)).thenReturn("task-1");

        service.publish(1L);

        assertEquals(ImprovementCaseStatus.PUBLISHING.name(), row.getStatus());
        assertEquals("task-1", row.getPublishTaskId());
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId("task-1");
        task.setStatus(RuntimePublishStatus.APPLIED.name());
        task.setRevision("revision-42");
        when(publishTaskMapper.selectById("task-1")).thenReturn(task);
        when(signalMapper.badcaseSignalCount("tenant-a", "signal-a")).thenReturn(4L);
        claim(row, "worker-a");

        service.processAutomation(row);

        assertEquals(ImprovementCaseStatus.OBSERVING.name(), row.getStatus());
        assertEquals("revision-42", row.getPublishRevision());
        assertEquals(4L, row.getBaselineSignalCount());
        row.setObservationEndsAtMs(System.currentTimeMillis() - 1L);
        when(signalMapper.exposureCalls(any(), any(), anyLong(), anyLong())).thenReturn(25L);
        claim(row, "worker-b");

        service.processAutomation(row);

        assertEquals(ImprovementCaseStatus.VERIFIED.name(), row.getStatus());
        assertEquals(ImprovementEffectStatus.EFFECTIVE.name(), row.getEffectStatus());
        assertEquals(25L, row.getObservedCalls());
    }

    @Test
    void observe_shouldMarkRecurrenceIneffectiveAndLowTrafficInconclusive() throws Exception {
        AgentImprovementCase recurrence = observingRow();
        when(caseMapper.lockById(1L)).thenReturn(recurrence);
        when(signalMapper.badcaseSignalCount("tenant-a", "signal-a")).thenReturn(11L);
        when(signalMapper.exposureCalls(any(), any(), anyLong(), anyLong())).thenReturn(30L);

        service.processAutomation(recurrence);

        assertEquals(ImprovementCaseStatus.INEFFECTIVE.name(), recurrence.getStatus());
        assertEquals(1L, recurrence.getObservedSignals());

        AgentImprovementCase lowTraffic = observingRow();
        lowTraffic.setObservationEndsAtMs(System.currentTimeMillis() - 1L);
        when(caseMapper.lockById(1L)).thenReturn(lowTraffic);
        when(signalMapper.badcaseSignalCount("tenant-a", "signal-a")).thenReturn(10L);
        when(signalMapper.exposureCalls(any(), any(), anyLong(), anyLong())).thenReturn(3L);

        service.processAutomation(lowTraffic);

        assertEquals(ImprovementCaseStatus.INCONCLUSIVE.name(), lowTraffic.getStatus());
        assertEquals(ImprovementEffectStatus.INCONCLUSIVE.name(), lowTraffic.getEffectStatus());
    }

    private AgentImprovementCase row(ImprovementCaseStatus status,
                                     EvalVersionBinding candidate) throws Exception {
        AgentImprovementCase row = new AgentImprovementCase();
        row.setId(1L);
        row.setTenantId("tenant-a");
        row.setSourceType(ImprovementSourceType.BADCASE.name());
        row.setSourceKey("badcase-1");
        row.setSignalHash("signal-a");
        row.setSourceSignalCount(1L);
        row.setOwnerId("alice");
        row.setSlaDueAtMs(System.currentTimeMillis() + 60000L);
        row.setStatus(status.name());
        row.setAgentId(7L);
        row.setAgentCode("support-agent");
        row.setArtifactType("AGENT_RUNTIME");
        row.setArtifactVersion(fingerprint(candidate));
        row.setCandidateVersionsJson(objectMapper.writeValueAsString(candidate));
        row.setEvalType(EvalType.INTENT.name());
        row.setEvalCaseId("case-target");
        row.setReevaluationStatus(ImprovementReevaluationStatus.NOT_RUN.name());
        row.setEffectStatus(ImprovementEffectStatus.NOT_STARTED.name());
        row.setObservedCalls(0L);
        row.setObservedSignals(0L);
        row.setNextActionAtMs(Long.MAX_VALUE);
        row.setLeaseUntilMs(0L);
        row.setAutomationFailures(0);
        row.setCreatedAtMs(1L);
        row.setUpdatedAtMs(1L);
        return row;
    }

    private AgentImprovementCase observingRow() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.OBSERVING, candidate("model-v1"));
        row.setPublishRevision("revision-42");
        row.setBaselineSignalCount(10L);
        row.setObservationStartedAtMs(System.currentTimeMillis() - 2000L);
        row.setObservationEndsAtMs(System.currentTimeMillis() + 60000L);
        row.setMinExposureCalls(20);
        row.setMaxRecurrenceSignals(0);
        row.setEffectStatus(ImprovementEffectStatus.OBSERVING.name());
        claim(row, "worker-a");
        return row;
    }

    private void claim(AgentImprovementCase row, String owner) {
        row.setLeaseOwner(owner);
        row.setLeaseUntilMs(System.currentTimeMillis() + 60000L);
    }

    private EvalComparison comparison(EvalVersionBinding binding, List<String> currentFailures,
                                      List<String> baselineFailures) {
        EvalRun current = run("run-current", binding, currentFailures);
        EvalRun baseline = run("run-baseline", binding, baselineFailures);
        return EvalComparison.of(current, baseline);
    }

    private EvalRun run(String id, EvalVersionBinding binding, List<String> failedCaseIds) {
        return new EvalRun(id, EvalType.INTENT, 2, 2 - failedCaseIds.size(),
            failedCaseIds.isEmpty() ? 1.0d : 0.5d, 1.0d, failedCaseIds, List.of(), Map.of(),
            EvalTrigger.MANUAL, 2, binding, null, System.currentTimeMillis());
    }

    private EvalVersionBinding candidate(String modelVersion) {
        return new EvalVersionBinding("", "", modelVersion, "prompt-v1", "agent-v1", "",
            "tool-v1", "", "");
    }

    private EvalVersionBinding completeBinding(String modelVersion) {
        return new EvalVersionBinding("dataset-v1", "dataset-hash-v1", modelVersion, "prompt-v1",
            "agent-v1", "knowledge-v1", "tool-v1", "judge-v1", "rubric-v1");
    }

    private String fingerprint(EvalVersionBinding binding) {
        return EvalFingerprint.of("agent-improvement-runtime-v1", binding.datasetVersion(),
            binding.datasetFingerprint(), binding.modelVersion(), binding.promptVersion(),
            binding.agentVersion(), binding.knowledgeBaseVersion(), binding.toolVersion(),
            binding.judgeVersion(), binding.rubricVersion());
    }
}
