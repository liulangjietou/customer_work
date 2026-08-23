package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.PreparedRuntimeConfig;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto.EvalGateOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity.EvalGateOverrideEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity.EvalGatePolicyEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.mapper.EvalGateOverrideMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.mapper.EvalGatePolicyMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalRunStore;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalReleaseGateServiceTest {

    private EvalGatePolicyMapper policyMapper;
    private EvalGateOverrideMapper overrideMapper;
    private RuntimePublishTaskMapper taskMapper;
    private RuntimePublishTaskService taskService;
    private EvalGatewayProvider gatewayProvider;
    private EvalReleaseGateService service;

    @BeforeEach
    void setUp() {
        policyMapper = mock(EvalGatePolicyMapper.class);
        overrideMapper = mock(EvalGateOverrideMapper.class);
        taskMapper = mock(RuntimePublishTaskMapper.class);
        taskService = mock(RuntimePublishTaskService.class);
        gatewayProvider = mock(EvalGatewayProvider.class);
        service = new EvalReleaseGateService(policyMapper, overrideMapper, taskMapper,
            taskService, gatewayProvider, new EvalGateEvaluator());
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void noPolicyShouldRecordNotRequiredAndKeepExistingPublishChain() {
        RuntimePublishTask task = task();
        PreparedRuntimeConfig prepared = prepared();
        when(policyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        EvalGateDecision decision = service.evaluateAndRecord(task, prepared);

        assertEquals(EvalGateStatus.NOT_REQUIRED, decision.status());
        verify(taskService).recordGateDecision(eq(task), any(), eq("[]"), any(),
            eq(EvalGateStatus.NOT_REQUIRED), eq(null));
        verify(gatewayProvider, never()).get();
    }

    @Test
    void matchingRunAboveThresholdShouldPassAndBindRunId() {
        EvalGatePolicyEntity policy = policy(EvalType.INTENT, 0.90);
        when(policyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(policy));
        EvalRunStore store = mock(EvalRunStore.class);
        when(gatewayProvider.get()).thenReturn(store);
        EvalRun current = run("run-1", 0.95);
        when(store.findRecent(EvalType.INTENT, 100)).thenReturn(List.of(current));
        when(store.findBaseline(EvalType.INTENT, "run-1")).thenReturn(java.util.Optional.empty());

        EvalGateDecision decision = service.evaluateAndRecord(task(), prepared());

        assertEquals(EvalGateStatus.PASSED, decision.status());
        verify(taskService).recordGateDecision(any(RuntimePublishTask.class), any(),
            eq("[\"run-1\"]"), any(), eq(EvalGateStatus.PASSED), isNull());
    }

    @Test
    void experimentActivationShouldGateBaselineAndPersistFullCandidateIdentity() throws Exception {
        EvalGatePolicyEntity policy = policy(EvalType.INTENT, 0.90);
        when(policyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(policy));
        EvalRunStore store = mock(EvalRunStore.class);
        when(gatewayProvider.get()).thenReturn(store);

        PreparedRuntimeConfig prepared = experimentPrepared();
        CustomerWorkRuntimeConfig baselineRuntime = new ObjectMapper().readValue(
            prepared.json(), CustomerWorkRuntimeConfig.class);
        baselineRuntime.setOnlineExperiment(null);
        EvalVersionBinding baselineCandidate = EvalVersionBinding.fromRuntimeConfig(baselineRuntime);
        EvalRun current = run("baseline-run", 0.96, baselineCandidate);
        when(store.findRecent(EvalType.INTENT, 100)).thenReturn(List.of(current));
        when(store.findBaseline(EvalType.INTENT, "baseline-run"))
            .thenReturn(java.util.Optional.empty());

        RuntimePublishTask task = task();
        task.setExperimentId(77L);
        task.setExperimentPublishAction(ModelExperimentPublishAction.ACTIVATE.name());
        EvalGateDecision decision = service.evaluateAndRecord(task, prepared);

        assertEquals(EvalGateStatus.PASSED, decision.status());
        assertTrue(decision.checks().get(0).notices().stream()
            .anyMatch(notice -> notice.contains("基线候选")));
        ArgumentCaptor<String> persistedCandidate = ArgumentCaptor.forClass(String.class);
        verify(taskService).recordGateDecision(eq(task), persistedCandidate.capture(),
            eq("[\"baseline-run\"]"), any(), eq(EvalGateStatus.PASSED), isNull());
        assertTrue(persistedCandidate.getValue().contains(prepared.versionBinding().modelVersion()));
        assertTrue(!prepared.versionBinding().modelVersion().equals(baselineCandidate.modelVersion()));
    }

    @Test
    void experimentDeactivationShouldBypassPoliciesAndNeverReadEvalStore() {
        RuntimePublishTask task = task();
        task.setExperimentId(77L);
        task.setExperimentPublishAction(ModelExperimentPublishAction.DEACTIVATE.name());

        EvalGateDecision decision = service.evaluateAndRecord(task, prepared());

        assertEquals(EvalGateStatus.NOT_REQUIRED, decision.status());
        verify(policyMapper, never()).selectList(any(QueryWrapper.class));
        verify(gatewayProvider, never()).get();
        verify(taskService).recordGateDecision(eq(task), any(), eq("[]"), any(),
            eq(EvalGateStatus.NOT_REQUIRED), isNull());
    }

    @Test
    void emergencyOverrideShouldBeTenantScopedAndAppendAuditBeforeRequeue() {
        RuntimePublishTask task = task();
        task.setStatus(RuntimePublishStatus.BLOCKED.name());
        task.setGateStatus(EvalGateStatus.BLOCKED.name());
        task.setGateDecisionJson("{\"status\":\"BLOCKED\"}");
        when(taskMapper.selectOne(any(QueryWrapper.class))).thenReturn(task);
        doAnswer(invocation -> {
            EvalGateOverrideEntity audit = invocation.getArgument(0);
            audit.setId(77L);
            return 1;
        }).when(overrideMapper).insert(any(EvalGateOverrideEntity.class));

        service.override("task-1", new EvalGateOverrideRequest(" 生产事故紧急恢复 "), 9L);

        ArgumentCaptor<EvalGateOverrideEntity> audit = ArgumentCaptor.forClass(EvalGateOverrideEntity.class);
        verify(overrideMapper).insert(audit.capture());
        assertEquals("tenant-a", audit.getValue().getTenantId());
        assertEquals("hash-1", audit.getValue().getCandidateContentHash());
        assertEquals("生产事故紧急恢复", audit.getValue().getReason());
        verify(taskService).overrideGateBlocked("task-1", "tenant-a", 77L);

        ArgumentCaptor<QueryWrapper<RuntimePublishTask>> wrapper = wrapperCaptor();
        verify(taskMapper).selectOne(wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("tenant-a"));
    }

    @Test
    void otherTenantTaskShouldBeHiddenAsNotFoundAndNeverAudited() {
        when(taskMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        BizException error = assertThrows(BizException.class, () ->
            service.override("task-other", new EvalGateOverrideRequest("reason"), 9L));

        assertEquals(ResultCode.RESOURCE_NOT_FOUND, error.getResultCode());
        verify(overrideMapper, never()).insert(any(EvalGateOverrideEntity.class));
        verify(taskService, never()).overrideGateBlocked(any(), any(), any());
    }

    private EvalGatePolicyEntity policy(EvalType type, double minimum) {
        EvalGatePolicyEntity policy = new EvalGatePolicyEntity();
        policy.setTenantId("tenant-a");
        policy.setEvalType(type.name());
        policy.setEnabled(1);
        policy.setMinPrimaryMetric(minimum);
        policy.setCriticalCaseIdsJson("[]");
        policy.setJudgeErrorPolicy(JudgeErrorPolicy.BLOCK.name());
        policy.setRequireArtifactMatch(1);
        return policy;
    }

    private RuntimePublishTask task() {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId("task-1");
        task.setTenantId("tenant-a");
        task.setTargetId(42L);
        task.setContentHash("hash-1");
        task.setStatus(RuntimePublishStatus.PROCESSING.name());
        task.setGateStatus(EvalGateStatus.PENDING.name());
        return task;
    }

    private PreparedRuntimeConfig prepared() {
        return new PreparedRuntimeConfig("agent-a", "web", "data", "group", "rev-1",
            "hash-1", "{}", candidate());
    }

    private EvalRun run(String runId, double primary) {
        return run(runId, primary, new EvalVersionBinding(
            "dataset-1", "dataset-hash", "model-1", "prompt-1",
            "agent-1", "kb-1", "tool-1", "NOT_APPLICABLE", "rubric-1"));
    }

    private EvalRun run(String runId, double primary, EvalVersionBinding candidate) {
        return new EvalRun(runId, EvalType.INTENT, 10, 10, primary, 0.90,
            List.of(), List.of(), Map.of(), EvalTrigger.MANUAL, 10,
            new EvalVersionBinding("dataset-1", "dataset-hash", candidate.modelVersion(),
                candidate.promptVersion(), candidate.agentVersion(), "kb-1",
                candidate.toolVersion(), "NOT_APPLICABLE", "rubric-1"), null, 1L);
    }

    private PreparedRuntimeConfig experimentPrepared() throws Exception {
        CustomerWorkRuntimeConfig runtime = new CustomerWorkRuntimeConfig();
        runtime.getModel().setProvider("openai");
        runtime.getModel().setName("gpt-test");
        runtime.getModel().setBaseUrl("https://example.test");
        CustomerWorkRuntimeConfig.Agent agent = new CustomerWorkRuntimeConfig.Agent();
        agent.setMaxIters(8);
        runtime.setAgent(agent);
        runtime.setSystemPrompt("stable prompt");
        CustomerWorkRuntimeConfig.OnlineExperiment experiment =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        experiment.setExperimentId(77L);
        experiment.setRevision(3);
        experiment.setAssignmentSalt("secret-salt");
        experiment.setTreatmentBps(5000);
        experiment.setExpiresAtEpochMs(System.currentTimeMillis() + 60_000L);
        runtime.setOnlineExperiment(experiment);
        EvalVersionBinding fullCandidate = EvalVersionBinding.fromRuntimeConfig(runtime);
        String json = new ObjectMapper().writeValueAsString(runtime);
        return new PreparedRuntimeConfig("agent-a", "web", "data", "group", "rev-exp",
            "hash-exp", json, fullCandidate);
    }

    private EvalVersionBinding candidate() {
        return new EvalVersionBinding("", "", "model-1", "prompt-1", "agent-1",
            "", "tool-1", "", "");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<QueryWrapper<RuntimePublishTask>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }
}
