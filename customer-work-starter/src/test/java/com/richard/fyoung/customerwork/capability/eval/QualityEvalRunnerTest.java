package com.richard.fyoung.customerwork.capability.eval;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回复质量评测执行器单测（LLM-as-Judge）：分数解析 / 评测流程 / 异常降级。
 * @author owlzhangfq@gmail.com
 */
class QualityEvalRunnerTest {

    private Msg userMsg(String text) {
        return Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text(text).build())
            .build();
    }

    private Msg assistantMsg(String text) {
        return Msg.builder()
            .role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text(text).build())
            .build();
    }

    @Test
    void parseScore_shouldExtractScoreFromText() {
        QualityEvalRunner runner = new QualityEvalRunner(null);
        assertEquals(5, runner.parseScore("SCORE: 5\n理由：完美"));
        assertEquals(3, runner.parseScore("SCORE: 3 - 一般"));
        assertEquals(1, runner.parseScore("score: 1\n太差了"));
    }

    @Test
    void parseScore_shouldClampToValidRange() {
        QualityEvalRunner runner = new QualityEvalRunner(null);
        assertEquals(5, runner.parseScore("SCORE: 9"));  // clamp to 5
        assertEquals(1, runner.parseScore("SCORE: 0"));  // clamp to 1
    }

    @Test
    void parseScore_shouldReturnDefault_whenNoScoreFound() {
        QualityEvalRunner runner = new QualityEvalRunner(null);
        assertEquals(3, runner.parseScore("没有分数信息"));
        assertEquals(3, runner.parseScore(""));
        assertEquals(3, runner.parseScore(null));
    }

    @Test
    void run_shouldEvaluateAllCases() {
        JudgeModel judgeModel = mock(JudgeModel.class);
        when(judgeModel.chat(any(Msg.class)))
            .thenReturn(assistantMsg("SCORE: 5\n\u7406\u7531\uff1a\u5f88\u597d"))
            .thenReturn(assistantMsg("SCORE: 2\n\u7406\u7531\uff1a\u592a\u5dee"));
        QualityEvalRunner runner = new QualityEvalRunner(judgeModel);
        List<QualityEvalCase> cases = List.of(
            new QualityEvalCase("c1", "退款问题", "应提供退款流程", "refund"),
            new QualityEvalCase("c2", "物流查询", "应提供物流信息", "order")
        );
        List<String> replies = List.of("已为您办理退款", "我不了解物流");

        QualityEvalReport report = runner.run(cases, replies);

        assertEquals(2, report.getTotal());
        assertEquals(3.5, report.getAvgScore(), 0.01);  // (5+2)/2 = 3.5
        assertEquals(1, report.getPassCount());  // 只有 5 分的通过
        assertEquals(1, report.getFailures().size());
    }

    @Test
    void run_shouldHandleJudgeFailure() {
        JudgeModel judgeModel = mock(JudgeModel.class);
        when(judgeModel.chat(any(Msg.class)))
            .thenThrow(new RuntimeException("model down"));

        QualityEvalRunner runner = new QualityEvalRunner(judgeModel);
        List<QualityEvalCase> cases = List.of(
            new QualityEvalCase("c1", "退款", "应提供流程", "refund")
        );
        List<String> replies = List.of("已退款");

        QualityEvalReport report = runner.run(cases, replies);

        assertEquals(1, report.getTotal());
        assertEquals(3, report.getAvgScore(), 0.01);  // 降级为中性分 3
        assertEquals(1, report.getPassCount());  // 3 >= 3，通过
    }

    @Test
    void run_shouldRejectMismatchedLengths() {
        QualityEvalRunner runner = new QualityEvalRunner(null);
        assertThrows(IllegalArgumentException.class, () ->
            runner.run(List.of(new QualityEvalCase("c1", "a", "b", "c")), List.of("reply1", "reply2"))
        );
    }
}
