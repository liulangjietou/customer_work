package com.richard.fyoung.customerwork.capability.eval;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 意图路由评测框架单测（离线确定性，借鉴 AliGo 测评系统）。
 *
 * <p>用 mock model 构造 orchestrator（评测只用其确定性的 {@code fastRouteIntent}，不触发模型），
 * 跑 classpath 评测集并对报告指标做断言——作为快车道质量的回归基线。</p>
 * @author owlzhangfq@gmail.com
 */
class IntentEvalRunnerTest {

    private MultiAgentOrchestrator orchestrator() {
        return new MultiAgentOrchestrator(mock(Model.class), new CustomerWorkProperties(),
            new MockOrderBackend(), new MockAfterSalesBackend(), new MockKnowledgeBackend());
    }

    @Test
    void shouldLoadDatasetFromClasspath() {
        IntentEvalRunner runner = new IntentEvalRunner(orchestrator());
        assertFalse(runner.loadDataset().isEmpty(), "应从 classpath 加载评测集");
    }

    @Test
    void fastLane_shouldMeetQualityBaseline() {
        IntentEvalRunner runner = new IntentEvalRunner(orchestrator());
        EvalReport report = runner.run();

        // 快车道质量回归基线：当前规则对评测集应 100% 判定正确（含模糊用例正确地交 LLM）
        assertEquals(report.getTotal(), report.getCorrect(),
            "快车道判定应全部正确，失败用例：\n" + String.join("\n", report.getFailures()));
        assertTrue(report.accuracy() >= 0.95, "准确率基线 ≥95%，实际=" + report.accuracy());
        assertTrue(report.fastLaneCoverage() >= 0.8,
            "明确意图覆盖率基线 ≥80%，实际=" + report.fastLaneCoverage());
    }
}
