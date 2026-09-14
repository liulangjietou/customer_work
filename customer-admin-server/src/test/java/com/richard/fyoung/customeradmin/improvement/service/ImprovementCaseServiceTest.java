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
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.config.ImprovementSignalGatewayProvider;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementCaseStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementEffectStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementReevaluationStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import com.richard.fyoung.customeradmin.improvement.dto.ImprovementEvalCaseRequest;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSignalGateway;
import com.richard.fyoung.customeradmin.improvement.jdbc.ImprovementSourceFact;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import com.richard.fyoung.customeradmin.improvement.mapper.ImprovementSignalMapper;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidateBindRequest;
import com.richard.fyoung.customeradmin.ops.dto.KnowledgeCandidatePublishRequest;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateBindingService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidateEvaluationService;
import com.richard.fyoung.customeradmin.ops.service.KnowledgeCandidatePublicationService;
import com.richard.fyoung.customerwork.capability.eval.EvalCaseStore;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshotter;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalFingerprint;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertNull;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.ID;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.evaluation;

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
    private EvalCaseStore evalCaseStore;
    private EvalDatasetSnapshot evalSnapshot;
    private KnowledgeCandidateBindingService knowledgeBindings;
    private KnowledgeCandidateEvaluationService knowledgeEvaluations;
    private KnowledgeCandidatePublicationService knowledgePublications;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
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
        var snapshotStore = new InMemoryEvalDatasetSnapshotStore();
        evalSnapshot = new EvalDatasetSnapshotter(snapshotStore).snapshot(EvalType.INTENT,
            List.of(Map.of("id", "case-target"), Map.of("id", "case-old")));
        var evalGateway = mock(EvalGatewayProvider.class);
        when(evalGateway.dataset()).thenReturn(new EvalGateway(null, null, snapshotStore, null));
        var datasetService = new EvalDatasetAdminService(evalGateway, objectMapper);

        evalCaseStore = mock(EvalCaseStore.class);
        ImprovementSignalGatewayProvider gatewayProvider = mock(ImprovementSignalGatewayProvider.class);
        when(gatewayProvider.get()).thenReturn(
            new ImprovementSignalGateway(signalMapper, evalCaseStore));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        knowledgeBindings = mock(KnowledgeCandidateBindingService.class);
        knowledgeEvaluations = mock(KnowledgeCandidateEvaluationService.class);
        knowledgePublications = mock(KnowledgeCandidatePublicationService.class);
        service = new ImprovementCaseService(caseMapper, gatewayProvider,
            mock(BadcaseGatewayProvider.class), mock(AiAgentMapper.class), publisher,
            evalAdminService, datasetService,
            knowledgeBindings, knowledgeEvaluations, knowledgePublications,
            publishTaskService, publishTaskMapper, properties, objectMapper,
            transactionManager);
    }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void knowledgeBindingPersistsImmutableInputBeforeAdvancingParent() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.OWNED);
        row.setArtifactVersion("previous-version"); row.setEvalRunId("previous-run");
        row.setPublishTaskId("previous-task");
        when(knowledgeBindings.prepare(1, HASH, knowledgeRequest())).thenReturn(binding);
        var result = TenantContext.callWith("tenant-a", () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42));
        assertEquals(KnowledgeCandidateBinding.ARTIFACT_TYPE, result.artifactType());
        assertEquals(binding.versions(), result.candidateVersions());
        assertEquals(binding.fingerprint(), result.artifactVersion());
        assertEquals(ImprovementCaseStatus.READY_FOR_REEVALUATION, result.status());
        assertNull(result.evalRunId()); assertNull(result.publishTaskId());
        var order = inOrder(caseMapper, knowledgeBindings);
        order.verify(caseMapper).selectById(1L);
        order.verify(knowledgeBindings).prepare(1, HASH, knowledgeRequest());
        order.verify(caseMapper).lockById(1L, "tenant-a");
        order.verify(knowledgeBindings).save(binding, 42);
        order.verify(caseMapper).updateById(row);
        verifyNoInteractions(publisher, publishTaskService, evalAdminService);
    }

    /** 原绑定只测试不可变行去重，未覆盖响应丢失重试对父记录已完成评测的影响。 */
    @Test
    void knowledgeBindingRetryMustPreserveAlreadyCompletedEvaluation() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_TO_PUBLISH);
        row.setEvalRunId("completed-run"); row.setReevaluationStatus("PASSED");
        when(knowledgeBindings.prepare(1, HASH, knowledgeRequest())).thenReturn(binding);
        var result = TenantContext.callWith("tenant-a", () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42));
        assertEquals("completed-run", result.evalRunId());
        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH, result.status());
        verify(knowledgeBindings, never()).save(any(), anyLong());
        verify(caseMapper, never()).updateById(any(AgentImprovementCase.class));
    }

    @Test
    void knowledgeBindingRechecksLockedStateBeforeSaving() throws Exception {
        var binding = binding("tenant-a");
        var snapshot = knowledgeRow(binding, ImprovementCaseStatus.OWNED);
        snapshot.setArtifactVersion("previous");
        var locked = row(ImprovementCaseStatus.REEVALUATING, binding.versions());
        locked.setSourceType("KNOWLEDGE_GAP"); locked.setSourceKey(HASH);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(locked);
        when(knowledgeBindings.prepare(1, HASH, knowledgeRequest())).thenReturn(binding);
        TenantContext.runWith("tenant-a", () -> assertThrows(BizException.class,
            () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42)));
        verify(knowledgeBindings, never()).save(any(), anyLong());
        verify(caseMapper, never()).updateById(any(AgentImprovementCase.class));
    }

    @Test
    void knowledgeInputsCannotAttachToAnotherTenantOrBadcase() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.OWNED);
        TenantContext.runWith("Tenant-A", () -> assertThrows(BizException.class,
            () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42)));
        row.setSourceType("BADCASE");
        TenantContext.runWith("tenant-a", () -> assertThrows(BizException.class,
            () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42)));
        verifyNoInteractions(knowledgeBindings, knowledgeEvaluations);
    }

    @Test
    void knowledgeReevaluationUsesItsPairedEvidenceAndPersistsBeforeReady() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        var evaluation = evaluation(binding);
        when(knowledgeEvaluations.run(eq(binding), eq(HASH), eq("复评"), anyLong())).thenReturn(evaluation);
        when(knowledgeEvaluations.failures(binding, evaluation)).thenReturn(List.of());
        var result = TenantContext.callWith("tenant-a", () -> service.reevaluateKnowledgeCandidate(1L, "复评"));
        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH, result.status());
        assertEquals(evaluation.current().runId(), result.evalRunId());
        var order = inOrder(knowledgeEvaluations, caseMapper);
        order.verify(caseMapper).lockById(1L, "tenant-a");
        order.verify(caseMapper).updateById(row);
        order.verify(knowledgeEvaluations).run(eq(binding), eq(HASH), eq("复评"), anyLong());
        order.verify(knowledgeEvaluations).failures(binding, evaluation);
        order.verify(caseMapper).lockById(1L, "tenant-a");
        order.verify(knowledgeEvaluations).save(evaluation);
        order.verify(caseMapper).updateById(row);
        verifyNoInteractions(evalAdminService, publisher, publishTaskService);
    }

    @Test
    void inputsChangedDuringReevaluationKeepTheResultButPreventReadiness() throws Exception {
        var binding = binding("tenant-a");
        knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        var evaluation = evaluation(binding);
        when(knowledgeEvaluations.run(eq(binding), eq(HASH), eq(null), anyLong())).thenReturn(evaluation);
        when(knowledgeEvaluations.failures(binding, evaluation)).thenReturn(List.of());
        doThrow(new BizException(com.richard.fyoung.customeradmin.common.result.ResultCode.CONFIG_EDIT_CONFLICT,
            "正式知识已改变")).when(knowledgeBindings).requireCurrent(binding, HASH);
        var result = TenantContext.callWith("tenant-a", () -> service.reevaluateKnowledgeCandidate(1L, null));
        assertEquals(ImprovementCaseStatus.REEVALUATION_FAILED, result.status());
        assertEquals(evaluation.current().runId(), result.evalRunId());
        assertTrue(result.reevaluationError().contains("正式知识已改变"));
        verify(knowledgeEvaluations).save(evaluation);
    }

    @Test
    void failedEvaluationPersistenceCannotAdvanceParentToReady() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        var evaluation = evaluation(binding);
        when(knowledgeEvaluations.run(eq(binding), eq(HASH), eq(null), anyLong())).thenReturn(evaluation);
        when(knowledgeEvaluations.failures(binding, evaluation)).thenReturn(List.of());
        doThrow(new IllegalStateException("evaluation store unavailable")).when(knowledgeEvaluations).save(evaluation);
        TenantContext.runWith("tenant-a", () -> assertThrows(IllegalStateException.class,
            () -> service.reevaluateKnowledgeCandidate(1L, null)));
        assertEquals(ImprovementCaseStatus.REEVALUATION_FAILED.name(), row.getStatus());
        assertNull(row.getEvalRunId());
        verifyNoInteractions(evalAdminService, publisher, publishTaskService);
    }

    @Test
    void legacyEndpointsCannotEvaluateOrPublishKnowledgeAsRuntimeConfiguration() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        TenantContext.runWith("tenant-a", () -> {
            assertThrows(BizException.class, () -> service.reevaluate(1L, null));
            assertThrows(BizException.class, () -> service.publish(1L));
        });
        assertEquals(ImprovementCaseStatus.READY_FOR_REEVALUATION.name(), row.getStatus());
        verifyNoInteractions(evalAdminService, publisher, publishTaskService, knowledgeEvaluations);
    }

    private AgentImprovementCase knowledgeRow(KnowledgeCandidateBinding binding, ImprovementCaseStatus status) throws Exception {
        var row = row(status, binding.versions());
        row.setAgentId(binding.agentId()); row.setAgentCode(binding.agentCode());
        row.setSourceType("KNOWLEDGE_GAP"); row.setSourceKey(HASH);
        row.setArtifactType(KnowledgeCandidateBinding.ARTIFACT_TYPE); row.setArtifactVersion(binding.fingerprint());
        row.setEvalType("QUALITY"); row.setEvalCaseId(binding.targetCaseId());
        when(caseMapper.selectById(1L)).thenReturn(row); when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(knowledgeBindings.require(1, binding.fingerprint())).thenReturn(binding);
        return row;
    }

    private KnowledgeCandidateBindRequest knowledgeRequest() {
        return new KnowledgeCandidateBindRequest(ID, 2, 7L, 11L, 12L, "release-1", "target");
    }

    /** 旧测试只覆盖一次完整请求，没有复现上次执行结束时下一次执行已经开始的情况。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void previousReevaluationSuccessOrFailureCannotOverwriteTheReplacementAttempt(boolean failure) throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        var result = evaluation(binding);
        when(knowledgeEvaluations.run(eq(binding), eq(HASH), eq(null), anyLong())).thenAnswer(call -> {
            // 模拟超时已恢复、另一请求已开始同一候选的新执行；父记录再次处于 REEVALUATING。
            row.setReevaluationAttemptId("replacement-attempt");
            row.setReevaluationDeadlineAtMs(System.currentTimeMillis() + 60000);
            if (failure) throw new IllegalStateException("previous request failed late");
            return result;
        });
        when(knowledgeEvaluations.failures(binding, result)).thenReturn(List.of());
        TenantContext.runWith("tenant-a", () -> assertThrows(RuntimeException.class,
            () -> service.reevaluateKnowledgeCandidate(1L, null)));
        assertEquals("REEVALUATING", row.getStatus());
        assertEquals("replacement-attempt", row.getReevaluationAttemptId());
        assertNull(row.getEvalRunId());
        verify(knowledgeEvaluations, never()).save(any());
    }

    @Test
    void completedResultPastItsDeadlineCannotMakeTheCandidatePublishable() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_FOR_REEVALUATION);
        var result = evaluation(binding);
        when(knowledgeEvaluations.run(eq(binding), eq(HASH), eq(null), anyLong())).thenAnswer(call -> {
            row.setReevaluationDeadlineAtMs(System.currentTimeMillis() - 1);
            return result;
        });
        when(knowledgeEvaluations.failures(binding, result)).thenReturn(List.of());
        TenantContext.runWith("tenant-a", () -> assertThrows(BizException.class,
            () -> service.reevaluateKnowledgeCandidate(1L, null)));
        assertEquals("REEVALUATION_FAILED", row.getStatus());
        assertNull(row.getEvalRunId());
        verify(knowledgeEvaluations, never()).save(any());
    }

    @Test
    void workerRecoversAnExpiredReevaluationWithoutCallingAnyModel() throws Exception {
        var row = knowledgeRow(binding("tenant-a"), ImprovementCaseStatus.REEVALUATING);
        row.setReevaluationStatus("RUNNING"); row.setReevaluationAttemptId("interrupted-attempt");
        row.setReevaluationDeadlineAtMs(System.currentTimeMillis() - 1); row.setLeaseOwner("worker");
        TenantContext.runWith("tenant-a", () -> service.processAutomation(row));
        assertEquals("REEVALUATION_FAILED", row.getStatus());
        assertEquals("FAILED", row.getReevaluationStatus());
        assertEquals(Long.MAX_VALUE, row.getNextActionAtMs());
        assertTrue(row.getReevaluationError().contains("重新"));
        verifyNoInteractions(knowledgeEvaluations, evalAdminService, knowledgePublications);
    }

    @Test
    void knowledgePublicationEnqueuesOnceAndKeepsOriginalActorOnRetry() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_TO_PUBLISH);
        row.setReevaluationStatus("PASSED"); row.setEvalRunId("run");
        var request = new KnowledgeCandidatePublishRequest(binding.fingerprint(), "run");
        var result = TenantContext.callWith("tenant-a", () -> service.publishKnowledgeCandidate(1L, request, 42));
        assertEquals(ImprovementCaseStatus.PUBLISHING, result.status());
        assertEquals(42L, row.getPublishRequestedBy());
        assertEquals(result.publishTaskId(), TenantContext.callWith("tenant-a",
            () -> service.publishKnowledgeCandidate(1L, request, 43)).publishTaskId());
        assertEquals(42L, row.getPublishRequestedBy());
        verify(knowledgePublications).reserve(binding, HASH, "run");
        verifyNoInteractions(publisher, publishTaskService, publishTaskMapper);
    }

    @Test
    void knowledgeReceiptAdvancesToPublishedWithoutPretendingOnlineEffectWasVerified() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgePublishingRow(binding);
        when(knowledgePublications.publish(binding, HASH, "run", "task", 42))
            .thenReturn(new com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationReceipt("task", "hash", 812, 1000));
        TenantContext.runWith("tenant-a", () -> service.processAutomation(row));
        assertEquals("PUBLISHED", row.getStatus());
        assertEquals("APPLIED", row.getPublishStatus());
        assertEquals("faq/812", row.getPublishRevision());
        assertEquals(1000L, row.getPublishedAtMs());
        assertEquals("NOT_STARTED", row.getEffectStatus());
        assertNull(row.getObservationStartedAtMs());
        verify(knowledgePublications).finish(binding, true);
        verifyNoInteractions(publishTaskMapper, signalMapper);
    }

    @Test
    void confirmedKnowledgeConflictUnlocksCandidateButUnknownResultKeepsOriginalTask() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgePublishingRow(binding);
        when(knowledgePublications.publish(binding, HASH, "run", "task", 42))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unknown commit result"))
            .thenThrow(new com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgePublicationConflictException("正式知识已变化"));
        TenantContext.runWith("tenant-a", () -> assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
            () -> service.processAutomation(row)));
        assertEquals("PUBLISHING", row.getStatus()); assertEquals("task", row.getPublishTaskId());
        verify(knowledgePublications, never()).finish(any(), org.mockito.ArgumentMatchers.anyBoolean());
        TenantContext.runWith("tenant-a", () -> service.processAutomation(row));
        assertEquals("PUBLISH_FAILED", row.getStatus());
        assertEquals("正式知识已变化", row.getLastError());
        verify(knowledgePublications).finish(binding, false);
    }

    @Test
    void failedKnowledgePublicationCanBeReboundAndReevaluatedEvenWithSameInputs() throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.PUBLISH_FAILED);
        row.setPublishTaskId("failed-task"); row.setPublishRequestedBy(42L);
        when(knowledgeBindings.prepare(1, HASH, knowledgeRequest())).thenReturn(binding);
        var result = TenantContext.callWith("tenant-a", () -> service.bindKnowledgeCandidate(1L, knowledgeRequest(), 42));
        assertEquals(ImprovementCaseStatus.READY_FOR_REEVALUATION, result.status());
        assertNull(result.publishTaskId()); assertNull(row.getPublishRequestedBy());
    }

    private AgentImprovementCase knowledgePublishingRow(KnowledgeCandidateBinding binding) throws Exception {
        var row = knowledgeRow(binding, ImprovementCaseStatus.PUBLISHING);
        row.setEvalRunId("run"); row.setPublishTaskId("task"); row.setPublishRequestedBy(42L); row.setLeaseOwner("worker");
        return row;
    }

    /** 先前测试只证明服务端当前证据有效，未证明它仍是操作者在确认页审阅过的版本。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void stalePublicationReviewCannotPublishAnotherAdministratorsNewCandidate(boolean changedArtifact) throws Exception {
        var binding = binding("tenant-a");
        var row = knowledgeRow(binding, ImprovementCaseStatus.READY_TO_PUBLISH);
        row.setReevaluationStatus("PASSED"); row.setEvalRunId("current-run");
        var request = new KnowledgeCandidatePublishRequest(changedArtifact ? "f".repeat(64) : binding.fingerprint(),
            changedArtifact ? "current-run" : "previous-run");
        TenantContext.runWith("tenant-a", () -> assertThrows(BizException.class,
            () -> service.publishKnowledgeCandidate(1L, request, 42)));
        assertEquals("READY_TO_PUBLISH", row.getStatus());
        verifyNoInteractions(knowledgePublications);
    }

    /** 原测试只覆盖复评与发布，没有验证创建用例被拒绝时是否已在客服库写入。 */
    @ParameterizedTest
    @EnumSource(value = ImprovementCaseStatus.class,
        names = {"PUBLISHING", "OBSERVING", "VERIFIED", "CANCELLED", "REEVALUATING"})
    void createEvalCaseMustCheckLockedStateBeforeWriting(ImprovementCaseStatus state) throws Exception {
        AgentImprovementCase before = row(ImprovementCaseStatus.OWNED, candidate("model-v1"));
        before.setSourceType(ImprovementSourceType.KNOWLEDGE_GAP.name());
        before.setSourceKey("knowledge-gap-1");
        AgentImprovementCase locked = row(state, candidate("model-v1"));
        locked.setSourceType(ImprovementSourceType.KNOWLEDGE_GAP.name());
        locked.setSourceKey("knowledge-gap-1");
        when(caseMapper.selectById(1L)).thenReturn(before);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(locked);
        var fact = new ImprovementSourceFact();
        fact.setQuestion("退款进度如何");
        fact.setSignalHash("signal-a");
        when(signalMapper.findKnowledgeGap("tenant-a", before.getSourceKey())).thenReturn(fact);
        var request = new ImprovementEvalCaseRequest(
            "case-new", EvalType.INTENT, "refund", "refund");

        TenantContext.runWith("tenant-a", () ->
            assertThrows(BizException.class, () -> service.createEvalCase(1L, request, "operator-a")));

        verify(evalCaseStore, never()).save(any());
        assertEquals("case-target", locked.getEvalCaseId());
        assertEquals(state.name(), locked.getStatus());
    }

    @Test
    void createEvalCaseMustLockBeforeCreationAndBindAfterItSucceeds() throws Exception {
        AgentImprovementCase row = ownedKnowledgeGapRow();
        var request = new ImprovementEvalCaseRequest(
            "case-new", EvalType.INTENT, "refund", "refund");
        var result = TenantContext.callWith("tenant-a", () ->
            service.createEvalCase(1L, request, "operator-a"));

        var order = inOrder(caseMapper, evalCaseStore);
        order.verify(caseMapper).lockById(1L, "tenant-a");
        order.verify(evalCaseStore).save(any());
        order.verify(caseMapper).updateById(row);
        assertEquals("case-new", result.evalCaseId());
        assertEquals(ImprovementCaseStatus.READY_FOR_REEVALUATION, result.status());
        assertEquals(ImprovementReevaluationStatus.NOT_RUN, result.reevaluationStatus());
    }

    @Test
    void failedCaseCreationMustKeepThePriorBinding() throws Exception {
        AgentImprovementCase row = ownedKnowledgeGapRow();
        doThrow(new IllegalStateException("case store unavailable"))
            .when(evalCaseStore).save(any());
        var request = new ImprovementEvalCaseRequest(
            "case-new", EvalType.INTENT, "refund", "refund");
        TenantContext.runWith("tenant-a", () ->
            assertThrows(IllegalStateException.class, () -> service.createEvalCase(1L, request, "operator-a")));

        verify(caseMapper, never()).updateById(any(AgentImprovementCase.class));
        assertEquals("case-target", row.getEvalCaseId());
        assertEquals(ImprovementCaseStatus.OWNED.name(), row.getStatus());
    }

    @Test
    void reevaluate_shouldFreezeExactCandidateAndTargetRegressionCase() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_FOR_REEVALUATION, candidate);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(evalAdminService.trigger(EvalType.INTENT, "fix refund")).thenReturn(
            comparison(completeBinding("model-v1"), List.of(), List.of("case-old")));

        service.reevaluate(1L, "fix refund");

        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH.name(), row.getStatus());
        assertEquals(ImprovementReevaluationStatus.PASSED.name(), row.getReevaluationStatus());
        assertEquals("run-current", row.getEvalRunId());
    }

    @Test
    void reevaluateMustNotPassWhenTheTargetWasOmittedFromTheDataset() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_FOR_REEVALUATION,
            candidate("model-v1"));
        row.setEvalCaseId("case-disabled-and-omitted");
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(evalAdminService.trigger(EvalType.INTENT, null)).thenReturn(
            comparison(completeBinding("model-v1"), List.of(), List.of()));

        service.reevaluate(1L, null);

        assertEquals(ImprovementCaseStatus.REEVALUATION_FAILED.name(), row.getStatus());
        assertEquals(ImprovementReevaluationStatus.FAILED.name(), row.getReevaluationStatus());
        assertTrue(row.getReevaluationError().contains("目标回归用例未进入本次评测"));
        verify(publishTaskService, never()).enqueueAgent(any());
    }

    @Test
    void reevaluate_shouldRejectMismatchedCandidateAndStillFailingTargetCase() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_FOR_REEVALUATION, candidate);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
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
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate("model-v2"));

        assertThrows(BizException.class, () -> service.publish(1L));

        verify(publishTaskService, never()).enqueueAgent(any());
        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH.name(), row.getStatus());
    }

    @Test
    void publishMustNotTrustAPassedFlagWithoutAnEvaluationRecord() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, candidate("model-v1"));
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate("model-v1"));

        assertThrows(BizException.class, () -> service.publish(1L));

        verify(publishTaskService, never()).enqueueAgent(any());
    }

    @Test
    void publishMustRecheckHistoricalPassedRecordsForAnOmittedTarget() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, candidate("model-v1"));
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        row.setEvalRunId("run-current");
        row.setEvalCaseId("case-disabled-and-omitted");
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate("model-v1"));
        when(evalAdminService.comparison("run-current")).thenReturn(
            comparison(completeBinding("model-v1"), List.of(), List.of()));

        BizException rejected = assertThrows(BizException.class, () -> service.publish(1L));

        assertTrue(rejected.getMessage().contains("目标回归用例未进入本次评测"));
        verify(publishTaskService, never()).enqueueAgent(any());
    }

    @Test
    void publishMustNotEnqueueWhenTheEvaluationStoreIsUnavailable() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, candidate("model-v1"));
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        row.setEvalRunId("run-current");
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate("model-v1"));
        when(evalAdminService.comparison("run-current"))
            .thenThrow(new IllegalStateException("evaluation store unavailable"));

        assertThrows(IllegalStateException.class, () -> service.publish(1L));

        verify(publishTaskService, never()).enqueueAgent(any());
        assertEquals(ImprovementCaseStatus.READY_TO_PUBLISH.name(), row.getStatus());
    }

    @Test
    void publishAndAutomation_shouldReachVerifiedOnlyAfterAppliedRevisionExposure() throws Exception {
        EvalVersionBinding candidate = candidate("model-v1");
        AgentImprovementCase row = row(ImprovementCaseStatus.READY_TO_PUBLISH, candidate);
        row.setReevaluationStatus(ImprovementReevaluationStatus.PASSED.name());
        row.setEvalRunId("run-current");
        when(caseMapper.selectById(1L)).thenReturn(row);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        when(publisher.previewVersionBinding(7L)).thenReturn(candidate);
        when(publishTaskService.enqueueAgent(7L)).thenReturn("task-1");
        when(evalAdminService.comparison("run-current")).thenReturn(
            comparison(completeBinding("model-v1"), List.of(), List.of("case-old")));

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

    /**
     * 门禁阻断必须停掉轮询并把原因抬上来。
     *
     * <p>此前 BLOCKED 落在 refreshPublish 的兜底 else 分支里被当成"进行中"，而调度器的
     * findDueCandidates 只认 PENDING 与租约过期的 PROCESSING、永远不会再捞 BLOCKED——
     * 于是这条 case 每个扫描周期被捞一次、判一次、再排下一次，状态永远停在 PUBLISHING，
     * lastError 恒为空所以面板的错误提示条也不显示。</p>
     */
    @Test
    void refreshPublish_shouldStopPollingAndSurfaceReasonWhenGateBlocked() throws Exception {
        AgentImprovementCase row = publishingRow("task-blocked");
        RuntimePublishTask task = publishTask("task-blocked", RuntimePublishStatus.BLOCKED);
        task.setLastError("回归指标未达标：intent 通过率 0.62 低于阈值 0.80");
        when(publishTaskMapper.selectById("task-blocked")).thenReturn(task);
        claim(row, "worker-blocked");

        service.processAutomation(row);

        assertEquals(ImprovementCaseStatus.PUBLISH_FAILED.name(), row.getStatus(),
            "BLOCKED 不会自行推进，必须离开 PUBLISHING，否则面板上永远是不动的「发布中」");
        assertEquals(Long.MAX_VALUE, row.getNextActionAtMs(),
            "BLOCKED 必须停掉轮询：调度器不会再捞它，继续排下一次就是无限循环");
        assertEquals("回归指标未达标：intent 通过率 0.62 低于阈值 0.80", row.getLastError(),
            "门禁失败摘要要抬到面板上，运营才知道是被门禁拦住而不是责任人拖了");
    }

    @Test
    void refreshPublish_shouldKeepPollingWhileStillAdvancing() throws Exception {
        for (RuntimePublishStatus advancing : List.of(
            RuntimePublishStatus.PENDING, RuntimePublishStatus.PROCESSING,
            RuntimePublishStatus.PUBLISHED, RuntimePublishStatus.PARTIAL)) {

            String taskId = "task-" + advancing.name();
            AgentImprovementCase row = publishingRow(taskId);
            when(publishTaskMapper.selectById(taskId)).thenReturn(publishTask(taskId, advancing));
            claim(row, "worker-" + advancing.name());

            service.processAutomation(row);

            assertEquals(ImprovementCaseStatus.PUBLISHING.name(), row.getStatus(),
                advancing + " 仍会自行推进，不该提前离开 PUBLISHING");
            assertTrue(row.getNextActionAtMs() < Long.MAX_VALUE,
                advancing + " 仍会自行推进，必须排下一次扫描");
        }
    }

    @Test
    void refreshPublish_shouldStopPollingWhenPublishSettledAsFailure() throws Exception {
        for (RuntimePublishStatus settled : List.of(
            RuntimePublishStatus.FAILED, RuntimePublishStatus.SUPERSEDED)) {

            String taskId = "task-" + settled.name();
            AgentImprovementCase row = publishingRow(taskId);
            RuntimePublishTask task = publishTask(taskId, settled);
            task.setLastError("实例拒绝：schema 校验失败");
            when(publishTaskMapper.selectById(taskId)).thenReturn(task);
            claim(row, "worker-" + settled.name());

            service.processAutomation(row);

            assertEquals(ImprovementCaseStatus.PUBLISH_FAILED.name(), row.getStatus(), settled.name());
            assertEquals(Long.MAX_VALUE, row.getNextActionAtMs(), settled.name());
            assertEquals("实例拒绝：schema 校验失败", row.getLastError(), settled.name());
        }
    }

    /** 处于 PUBLISHING、已关联发布任务的 case（refreshPublish 的入口条件）。 */
    private AgentImprovementCase publishingRow(String taskId) throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.PUBLISHING, candidate("model-v1"));
        row.setPublishTaskId(taskId);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        return row;
    }

    private RuntimePublishTask publishTask(String id, RuntimePublishStatus status) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(id);
        task.setStatus(status.name());
        task.setRevision("revision-42");
        return task;
    }

    @Test
    void observe_shouldMarkRecurrenceIneffectiveAndLowTrafficInconclusive() throws Exception {
        AgentImprovementCase recurrence = observingRow();
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(recurrence);
        when(signalMapper.badcaseSignalCount("tenant-a", "signal-a")).thenReturn(11L);
        when(signalMapper.exposureCalls(any(), any(), anyLong(), anyLong())).thenReturn(30L);

        service.processAutomation(recurrence);

        assertEquals(ImprovementCaseStatus.INEFFECTIVE.name(), recurrence.getStatus());
        assertEquals(1L, recurrence.getObservedSignals());

        AgentImprovementCase lowTraffic = observingRow();
        lowTraffic.setObservationEndsAtMs(System.currentTimeMillis() - 1L);
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(lowTraffic);
        when(signalMapper.badcaseSignalCount("tenant-a", "signal-a")).thenReturn(10L);
        when(signalMapper.exposureCalls(any(), any(), anyLong(), anyLong())).thenReturn(3L);

        service.processAutomation(lowTraffic);

        assertEquals(ImprovementCaseStatus.INCONCLUSIVE.name(), lowTraffic.getStatus());
        assertEquals(ImprovementEffectStatus.INCONCLUSIVE.name(), lowTraffic.getEffectStatus());
    }

    private AgentImprovementCase ownedKnowledgeGapRow() throws Exception {
        AgentImprovementCase row = row(ImprovementCaseStatus.OWNED, candidate("model-v1"));
        row.setSourceType(ImprovementSourceType.KNOWLEDGE_GAP.name());
        row.setSourceKey("knowledge-gap-1");
        when(caseMapper.lockById(1L, "tenant-a")).thenReturn(row);
        var fact = new ImprovementSourceFact();
        fact.setQuestion("退款进度如何");
        fact.setSignalHash("signal-a");
        when(signalMapper.findKnowledgeGap("tenant-a", row.getSourceKey())).thenReturn(fact);
        return row;
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
        return new EvalVersionBinding(evalSnapshot.versionId(), evalSnapshot.contentHash(), modelVersion, "prompt-v1",
            "agent-v1", "knowledge-v1", "tool-v1", "judge-v1", "rubric-v1");
    }

    private String fingerprint(EvalVersionBinding binding) {
        return EvalFingerprint.of("agent-improvement-runtime-v1", binding.datasetVersion(),
            binding.datasetFingerprint(), binding.modelVersion(), binding.promptVersion(),
            binding.agentVersion(), binding.knowledgeBaseVersion(), binding.toolVersion(),
            binding.judgeVersion(), binding.rubricVersion());
    }
}
