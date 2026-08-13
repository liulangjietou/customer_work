package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测编排单测：验证"跑标准集 → 落库 → 对比上一版"这条链路真的闭合。
 *
 * <p>此前 Runner 只能算出一份内存里的报告、没有任何调用方；这里断言的正是补上的那一段——
 * 每次运行留下可查记录，且第二次运行能自动拿第一次当基线。</p>
 * @author owlzhangfq@gmail.com
 */
class EvalServiceTest {

    private EvalRunStore store;
    private EvalService service;

    private MultiAgentOrchestrator orchestrator() {
        return new MultiAgentOrchestrator(mock(Model.class), new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> absentProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryEvalRunStore();
        service = new EvalService(new IntentEvalRunner(orchestrator()), store,
            new InMemoryEvalCaseStore(), absentProvider(), absentProvider(), absentProvider());
    }

    @Test
    void firstIntentRun_shouldPersistAndReportFirstRun() {
        EvalComparison comparison = service.runIntent(EvalTrigger.MANUAL, "首次基线");

        assertEquals(EvalComparison.Verdict.FIRST_RUN, comparison.verdict());
        assertNull(comparison.baseline(), "首次运行没有基线");
        assertTrue(store.find(comparison.current().runId()).isPresent(), "运行记录应已落库");
        assertEquals(EvalTrigger.MANUAL, comparison.current().trigger());
        assertEquals("首次基线", comparison.current().remark());
    }

    @Test
    void secondIntentRun_shouldCompareAgainstFirst() {
        // 两次运行几乎必然落在同一毫秒（意图评测是纯内存计算）——基线按写入顺序取而非时间戳，
        // 正是为了让这种情况也能正确拿到上一版。曾经按 created_at_ms 定序，这条用例在全量跑时随机挂
        EvalComparison first = service.runIntent(EvalTrigger.SCHEDULED, null);
        EvalComparison second = service.runIntent(EvalTrigger.MANUAL, null);

        assertNotNull(second.baseline(), "第二次运行应自动拿第一次当基线");
        assertEquals(first.current().runId(), second.baseline().runId());
        // 规则快车道是确定性的，同一份评测集两次跑结果必然一致
        assertEquals(EvalComparison.Verdict.UNCHANGED, second.verdict());
        assertTrue(second.regressions().isEmpty());
    }

    @Test
    void intentRun_shouldNormalizePrimaryMetricToAccuracy() {
        EvalComparison comparison = service.runIntent(EvalTrigger.API, null);
        EvalRun run = comparison.current();

        assertEquals(EvalType.INTENT, run.evalType());
        assertTrue(run.primaryMetric() >= 0.0d && run.primaryMetric() <= 1.0d, "主指标归一化到 0-1");
        assertEquals(run.total(), run.datasetSize());
        assertTrue(run.metrics().containsKey("accuracy"), "原始指标要完整保留，归一化不丢信息");
        assertTrue(run.metrics().containsKey("fastLaneCoverage"));
    }

    @Test
    void recent_shouldReturnNewestFirst() {
        service.runIntent(EvalTrigger.MANUAL, "第一次");
        EvalComparison second = service.runIntent(EvalTrigger.MANUAL, "第二次");

        List<EvalRun> recent = service.recent(EvalType.INTENT, 10);

        assertEquals(2, recent.size());
        assertEquals(second.current().runId(), recent.get(0).runId(), "最新的排在最前");
    }

    @Test
    void compareWithBaseline_shouldReplayHistoricalComparison() {
        EvalComparison first = service.runIntent(EvalTrigger.MANUAL, null);
        EvalComparison second = service.runIntent(EvalTrigger.MANUAL, null);

        EvalComparison replayed = service.compareWithBaseline(second.current().runId()).orElseThrow();

        assertEquals(second.current().runId(), replayed.current().runId());
        assertEquals(first.current().runId(), replayed.baseline().runId());
    }

    @Test
    void compareWithBaseline_shouldBeEmptyForUnknownRun() {
        assertTrue(service.compareWithBaseline("not-exists").isEmpty());
    }

    @Test
    void qualityRun_withoutJudgeModel_shouldFailFastWithReason() {
        // 缺 JudgeModel 时直接抛错说明原因，而不是返回一份全中性分的假报告
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.runQuality(EvalTrigger.MANUAL, null));

        assertTrue(error.getMessage().contains("JudgeModel"), "错误信息要指出缺的是什么");
    }

    @Test
    @SuppressWarnings("unchecked")
    void qualityRun_withoutChatService_shouldFailFastWithReason() {
        ObjectProvider<JudgeModel> judgeProvider = mock(ObjectProvider.class);
        when(judgeProvider.getIfAvailable()).thenReturn(message -> null);
        EvalService withJudge = new EvalService(new IntentEvalRunner(orchestrator()), store,
            new InMemoryEvalCaseStore(), judgeProvider, absentProvider(), absentProvider());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> withJudge.runQuality(EvalTrigger.MANUAL, null));

        assertTrue(error.getMessage().contains("CustomerServiceService"));
    }

    @Test
    void storeShouldBeTheSingleSourceOfHistory() {
        EvalComparison comparison = service.runIntent(EvalTrigger.MANUAL, null);

        assertSame(EvalType.INTENT, store.findRecent(EvalType.INTENT, 1).get(0).evalType());
        assertTrue(store.findBaseline(EvalType.INTENT, comparison.current().runId()).isEmpty(),
            "本次运行不能把自己当成自己的基线");
    }

    @Test
    void unrelatedEvalType_shouldNotBecomeBaseline() {
        service.runIntent(EvalTrigger.MANUAL, null);

        assertTrue(store.findRecent(EvalType.QUALITY, 10).isEmpty(),
            "两类评测口径不同，不能混进同一条趋势线");
    }
}
