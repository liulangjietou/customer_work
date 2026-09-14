package com.richard.fyoung.customeradmin.ops.service;

import org.junit.jupiter.api.Assertions;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.data.calllog.AgentCallTimingMiddleware;
import com.richard.fyoung.customerwork.data.calllog.AgentCallRecord;
import com.richard.fyoung.customerwork.core.middleware.PromptInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.IndirectInjectionGuardMiddleware;
import com.richard.fyoung.customerwork.capability.eval.KnowledgeCandidateTrialRunner;
import com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.HASH;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.binding;
import static com.richard.fyoung.customeradmin.ops.KnowledgeCandidateTestInputs.change;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.eval.config.EvalGateway;
import com.richard.fyoung.customeradmin.eval.config.EvalGatewayProvider;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.ops.config.OpsGatewayProvider;
import com.richard.fyoung.customeradmin.ops.domain.KnowledgeCandidateBinding;
import com.richard.fyoung.customeradmin.ops.jdbc.OpsGateway;
import com.richard.fyoung.customeradmin.ops.store.KnowledgeCandidateEvaluationStore;
import com.richard.fyoung.customerwork.capability.eval.InMemoryEvalDatasetSnapshotStore;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.entity.KnowledgeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.KnowledgeMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** 实际 ReAct 与实际评分器的组合验证；脚本模型只控制响应，不代替最终真实模型验收。 */
class KnowledgeCandidateEvaluationServiceTest {
    private final KnowledgeCandidateBindingService bindings = mock(KnowledgeCandidateBindingService.class);
    private final KnowledgeTrialModelService models = mock(KnowledgeTrialModelService.class);
    private final OpsGatewayProvider gateway = mock(OpsGatewayProvider.class);
    private final KnowledgeMapper knowledgeMapper = mock(KnowledgeMapper.class);
    private final KnowledgeCandidateEvaluationStore store = mock(KnowledgeCandidateEvaluationStore.class);
    private final Model mainModel = mock(Model.class);
    private final Model judgeModel = mock(Model.class);
    private KnowledgeCandidateBinding binding;
    private KnowledgeCandidateEvaluationService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("TenantA");
        binding = binding("TenantA");
        var snapshots = new InMemoryEvalDatasetSnapshotStore(); snapshots.saveIfAbsent(binding.dataset());
        var evalGateway = mock(EvalGatewayProvider.class);
        when(evalGateway.dataset()).thenReturn(new EvalGateway(null, null, snapshots, null));
        service = new KnowledgeCandidateEvaluationService(bindings, models, gateway,
            new EvalDatasetAdminService(evalGateway, new ObjectMapper()), store, new ObjectMapper(), KnowledgeCandidateTestInputs.trialRunner());
        var ops = mock(OpsGateway.class);
        when(gateway.get()).thenReturn(ops); when(ops.knowledgeMapper()).thenReturn(knowledgeMapper);
        when(models.build(binding.model())).thenReturn(mainModel);
        when(models.build(binding.judge())).thenReturn(judgeModel);
        when(mainModel.getModelName()).thenReturn("scripted-main");
        when(judgeModel.getModelName()).thenReturn("scripted-judge");
        var candidate = new KnowledgeDO(); candidate.setId(24L); candidate.setContent("候选开票规则"); candidate.setSource("候选");
        when(knowledgeMapper.searchSnapshot("纸质票", binding.knowledge().baselineCorpusJson())).thenReturn(List.of());
        when(knowledgeMapper.searchSnapshot("纸质票", binding.knowledge().corpusJson())).thenReturn(List.of(candidate));
        scriptedRetrieval();
        scores("5", "5", "5", "5");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void trialAndJudgeCallsAreMeteredAsEvaluationWithFrozenVersions() {
        new com.richard.fyoung.customeradmin.tenant.AdminTenantContextPropagationConfig().setUp();
        var records = new CopyOnWriteArrayList<AgentCallRecord>();
        var tenants = new CopyOnWriteArrayList<String>();
        var properties = new CustomerWorkProperties();
        properties.getCallLog().setEnabled(true);
        var runner = new KnowledgeCandidateTrialRunner(
            new AgentCallTimingMiddleware(properties,
                new ToolKindRegistry(), record -> { tenants.add(TenantContext.get()); records.add(record); }, null), null,
            new MaskingMiddleware(false, null),
            new PromptInjectionGuardMiddleware(false, "拒绝", List.of(), null),
            new IndirectInjectionGuardMiddleware(false, false, List.of(), null));
        service = new KnowledgeCandidateEvaluationService(bindings, models, gateway, mock(EvalDatasetAdminService.class),
            store, new ObjectMapper(), runner);
        doAnswer(invocation -> text("SCORE: 5").delayElements(java.time.Duration.ofMillis(1)))
            .when(judgeModel).stream(any(), any(), any());

        service.run(binding, HASH, "计量归因验证", Long.MAX_VALUE);

        assertEquals(8, records.size(), "两组各两条答复和四次 Judge 都必须计量");
        assertEquals(java.util.Collections.nCopies(8, "TenantA"), tenants, "异步评分完成后仍按发起租户记账");
        assertEquals("TenantA", TenantContext.require());
        assertTrue(records.stream().allMatch(record -> "EVALUATION".equals(record.sessionType().name())));
        assertTrue(records.stream().allMatch(record -> binding.agentCode().equals(record.agentCode())));
        assertTrue(records.stream().allMatch(record -> record.experimentAssignment() == null));
        assertEquals(8, records.stream().map(record -> record.requestId()).distinct().count());
        assertEquals(4, records.stream().filter(record -> binding.baselineVersions().equals(record.lineage().versionBinding())).count());
        assertEquals(4, records.stream().filter(record -> binding.versions().equals(record.lineage().versionBinding())).count());
        assertTrue(records.stream().allMatch(record -> record.modelMs() >= 0 && record.segmentCount() > 0));
        assertTrue(records.stream().allMatch(record -> record.totalTokens() != null && record.totalTokens() >= 15));
    }

    @Test
    void expiredOverallAttemptDoesNotResolveModelsOrStartAnotherEvaluation() {
        assertThrows(IllegalStateException.class, () -> service.run(binding, HASH, null, 1));
        verifyNoInteractions(models, mainModel, judgeModel, knowledgeMapper, store, bindings);
    }

    @Test
    void judgeSharesTheOverallDeadlineAndItsPendingSubscriptionIsCancelled() {
        var cancelled = new AtomicBoolean();
        org.mockito.Mockito.doReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)))
            .when(judgeModel).stream(any(), any(), any());
        assertThrows(RuntimeException.class,
            () -> service.run(binding, HASH, null, System.currentTimeMillis() + 1500));
        Assertions.assertTrue(cancelled.get());
        verify(judgeModel).stream(any(), any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void actualFrozenToolLoopGeneratesPairedRepliesAndExactVersionEvidence() {
        var evaluation = service.run(binding, HASH, "手工复评", Long.MAX_VALUE);
        assertEquals(List.of("基线答复", "基线答复"), evaluation.baselineReplies());
        assertEquals(List.of("候选答复", "候选答复"), evaluation.candidateReplies());
        assertEquals(List.of("target", "existing"), evaluation.candidateRecalledCaseIds());
        assertEquals(binding.versions(), evaluation.current().versionBinding());
        assertEquals(binding.baselineVersions(), evaluation.baseline().versionBinding());
        assertTrue(service.failures(binding, evaluation).isEmpty());
        verify(bindings).requireCurrent(binding, HASH);
        verify(knowledgeMapper, times(2)).searchSnapshot("纸质票", binding.knowledge().baselineCorpusJson());
        verify(knowledgeMapper, times(2)).searchSnapshot("纸质票", binding.knowledge().corpusJson());
        verifyNoMoreInteractions(knowledgeMapper);
        verifyNoInteractions(store);
        service.save(evaluation);
        verify(store).save(evaluation);
    }

    @Test
    void perfectJudgeScoreCannotReplaceCandidateRetrieval() {
        doReturn(text("没有检索的答复")).when(mainModel).stream(any(), any(), any());
        var evaluation = service.run(binding, HASH, null, Long.MAX_VALUE);
        assertEquals(1.0d, evaluation.current().primaryMetric());
        assertTrue(service.failures(binding, evaluation).stream().anyMatch(s -> s.contains("未实际检索")));
        verifyNoInteractions(knowledgeMapper);
    }

    @Test
    void unchangedAverageCannotHideAnotherCasesRegression() {
        scores("2", "5", "5", "2");
        var evaluation = service.run(binding, HASH, null, Long.MAX_VALUE);
        assertEquals(0, evaluation.comparison().primaryDelta());
        assertEquals(List.of("existing"), evaluation.comparison().regressions());
        assertTrue(service.failures(binding, evaluation).stream().anyMatch(s -> s.contains("新增回归")));
    }

    @Test
    void judgeFailureAndInsufficientQualityRemainFailedEvidence() {
        scores("invalid", "5", "2", "2");
        var evaluation = service.run(binding, HASH, null, Long.MAX_VALUE);
        assertFalse(evaluation.baseline().gatePassed());
        var failures = service.failures(binding, evaluation);
        assertTrue(failures.stream().anyMatch(s -> s.contains("Judge 运行不完整")));
        assertTrue(failures.stream().anyMatch(s -> s.contains("平均质量")));
        assertTrue(failures.stream().anyMatch(s -> s.contains("目标回归用例仍失败")));
    }

    @Test
    void staleInputsFailBeforeModelConstructionOrWrites() {
        doThrow(new BizException(ResultCode.CONFIG_EDIT_CONFLICT)).when(bindings).requireCurrent(binding, HASH);
        assertThrows(BizException.class, () -> service.run(binding, HASH, null, Long.MAX_VALUE));
        verifyNoInteractions(models, mainModel, judgeModel, gateway, knowledgeMapper, store);
    }

    @Test
    void reportCannotBeReusedForAnotherFrozenPromptOrTenant() {
        var evaluation = service.run(binding, HASH, null, Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> service.failures(change(binding, "systemPrompt", "其它提示词"), evaluation));
        TenantContext.set("tenanta");
        assertThrows(BizException.class, () -> service.save(evaluation));
        verifyNoInteractions(store);
    }

    @Test
    void reviewShowsExactPairedAnswersWithoutExposingTheCorpusOrEndpoint() throws Exception {
        var evaluation = service.run(binding, HASH, null, Long.MAX_VALUE);
        when(store.find("TenantA", 1, binding.fingerprint(), evaluation.current().runId())).thenReturn(Optional.of(evaluation));
        var review = service.review(binding, evaluation.current().runId());
        assertEquals("target", review.cases().get(0).caseId());
        assertEquals("基线答复", review.cases().get(0).baselineReply());
        assertEquals("候选答复", review.cases().get(0).candidateReply());
        assertTrue(review.cases().get(0).candidateRecalled());
        String json = new ObjectMapper().writeValueAsString(review);
        for (String privateValue : List.of("https://model.example", "https://judge.example", "冻结提示词", "corpusJson", "baselineCorpusJson")) {
            assertFalse(json.contains(privateValue));
        }
    }

    @Test
    void unevaluatedBindingHasCasesButNoInventedRepliesOrScores() {
        var review = service.review(binding, null);
        Assertions.assertNull(review.comparison());
        assertEquals(2, review.cases().size());
        assertTrue(review.cases().stream().allMatch(item -> item.baselineReply() == null && item.candidateReply() == null));
        verifyNoInteractions(store, models, mainModel, judgeModel, knowledgeMapper);
    }

    private void scriptedRetrieval() {
        var round = new AtomicInteger();
        when(mainModel.stream(any(), any(), any())).thenAnswer(invocation -> {
            List<ToolSchema> tools = invocation.getArgument(1);
            assertEquals(List.of("searchKnowledge"), tools.stream().map(ToolSchema::getName).toList());
            List<Msg> messages = invocation.getArgument(0);
            if (round.getAndIncrement() % 2 == 0) {
                var tool = new ToolUseBlock(UUID.randomUUID().toString(), "searchKnowledge",
                    Map.of("query", "纸质票"), "{\"query\":\"纸质票\"}", null);
                return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
                    .content(List.of(tool)).finishReason("tool_calls").build());
            }
            boolean candidate = messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(result -> result.getOutput().stream()).filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .anyMatch(text -> text.getText().contains("候选开票规则"));
            return text(candidate ? "候选答复" : "基线答复");
        });
    }

    private void scores(String... scores) {
        var index = new AtomicInteger();
        doAnswer(invocation -> {
            assertEquals(List.of(), invocation.getArgument(1));
            return text("SCORE: " + scores[index.getAndIncrement()]);
        }).when(judgeModel).stream(any(), any(), any());
    }

    private Flux<ChatResponse> text(String text) {
        return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString())
            .content(List.of(TextBlock.builder().text(text).build()))
            .usage(new io.agentscope.core.model.ChatUsage(10, 5, 0.0)).finishReason("stop").build());
    }
}
