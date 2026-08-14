package com.richard.fyoung.customerwork.capability.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本对比单测：验证"这版比上版好还是坏"的两个视角——总分方向与逐用例进出。
 *
 * <p>重点覆盖总分与回归相互独立这件事：准确率涨了但个别用例挂掉，是最容易被总分掩盖、
 * 也最需要人立刻去看的情形。</p>
 * @author owlzhangfq@gmail.com
 */
class EvalComparisonTest {

    private EvalRun run(double primary, List<String> failedIds, int datasetSize, long ts) {
        return run(primary, failedIds, datasetSize, ts, "fp-same");
    }

    private EvalRun run(double primary, List<String> failedIds, int datasetSize, long ts, String promptFp) {
        return new EvalRun("run-" + ts, EvalType.INTENT, datasetSize, datasetSize - failedIds.size(),
            primary, 0.8d, failedIds, List.of(), Map.of(), EvalTrigger.MANUAL, datasetSize,
            promptFp, null, ts);
    }

    @Test
    void firstRun_shouldHaveNoBaseline() {
        EvalComparison comparison = EvalComparison.of(run(0.9d, List.of(), 10, 1000L), null);

        assertEquals(EvalComparison.Verdict.FIRST_RUN, comparison.verdict());
        assertEquals(0.0d, comparison.primaryDelta(), "无基线时变化量为 0");
        assertTrue(comparison.regressions().isEmpty());
    }

    @Test
    void higherPrimaryMetric_shouldBeImproved() {
        EvalComparison comparison = EvalComparison.of(
            run(0.95d, List.of(), 10, 2000L), run(0.90d, List.of(), 10, 1000L));

        assertEquals(EvalComparison.Verdict.IMPROVED, comparison.verdict());
        assertEquals(0.05d, comparison.primaryDelta(), 1e-9);
    }

    @Test
    void lowerPrimaryMetric_shouldBeRegressed() {
        EvalComparison comparison = EvalComparison.of(
            run(0.80d, List.of(), 10, 2000L), run(0.90d, List.of(), 10, 1000L));

        assertEquals(EvalComparison.Verdict.REGRESSED, comparison.verdict());
    }

    @Test
    void tinyFloatDifference_shouldBeUnchanged() {
        // 容差之内：不设容差会让每次浮点尾差都被报成"有变化"，噪声淹没真信号
        EvalComparison comparison = EvalComparison.of(
            run(0.9000001d, List.of(), 10, 2000L), run(0.9d, List.of(), 10, 1000L));

        assertEquals(EvalComparison.Verdict.UNCHANGED, comparison.verdict());
    }

    @Test
    void shouldSeparateRegressionsFromFixes() {
        // 上版挂 a、b；这版挂 b、c —— a 被修好，c 是新挂的
        EvalComparison comparison = EvalComparison.of(
            run(0.9d, List.of("b", "c"), 10, 2000L),
            run(0.9d, List.of("a", "b"), 10, 1000L));

        assertEquals(List.of("c"), comparison.regressions(), "c 上版通过、这版失败，属回归");
        assertEquals(List.of("a"), comparison.fixes(), "a 上版失败、这版通过，属修复");
    }

    @Test
    void improvedTotalScore_shouldStillExposeRegression() {
        // 总分涨了，但有一个用例从通过变失败——这正是总分会掩盖、必须单独暴露的情形
        EvalComparison comparison = EvalComparison.of(
            run(0.95d, List.of("c"), 10, 2000L),
            run(0.90d, List.of("a"), 10, 1000L));

        assertEquals(EvalComparison.Verdict.IMPROVED, comparison.verdict(), "总分方向仍是上升");
        assertEquals(List.of("c"), comparison.regressions(), "回归项不因总分上涨而被吞掉");
        assertTrue(comparison.format().contains("regressions"), "文本摘要要带上回归项");
    }

    @Test
    void changedDatasetSize_shouldBeFlagged() {
        EvalComparison comparison = EvalComparison.of(
            run(0.95d, List.of(), 20, 2000L), run(0.90d, List.of(), 10, 1000L));

        assertTrue(comparison.datasetChanged(), "用例数从 10 变 20，两次指标不可直接比");
        assertTrue(comparison.format().contains("WARNING"), "摘要要给出不可比的提示");
    }

    @Test
    void sameDatasetSize_shouldNotBeFlagged() {
        EvalComparison comparison = EvalComparison.of(
            run(0.95d, List.of(), 10, 2000L), run(0.90d, List.of(), 10, 1000L));

        assertFalse(comparison.datasetChanged());
    }

    @Test
    void changedPrompt_shouldBeFlaggedForAttribution() {
        // 归因的支点：指标掉了先看这一位。变了就去比两版提示词全文
        EvalComparison comparison = EvalComparison.of(
            run(0.80d, List.of(), 10, 2000L, "fp-new"),
            run(0.90d, List.of(), 10, 1000L, "fp-old"));

        assertTrue(comparison.promptChanged());
        assertEquals(EvalComparison.Verdict.REGRESSED, comparison.verdict());
        assertTrue(comparison.format().contains("prompt changed"), "摘要要点明这次改过提示词");
    }

    @Test
    void samePrompt_shouldNotBeFlagged() {
        // 指纹没变而指标掉了：别再对着提示词逐字看，该去查模型或数据
        EvalComparison comparison = EvalComparison.of(
            run(0.80d, List.of(), 10, 2000L), run(0.90d, List.of(), 10, 1000L));

        assertFalse(comparison.promptChanged());
    }

    @Test
    void missingFingerprint_shouldNotClaimPromptChanged() {
        // 未启用追踪时无从判断，不做无根据的断言
        EvalComparison comparison = EvalComparison.of(
            run(0.80d, List.of(), 10, 2000L, ""),
            run(0.90d, List.of(), 10, 1000L, "fp-old"));

        assertFalse(comparison.promptChanged());
    }
}
