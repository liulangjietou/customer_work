package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalTrigger;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalGateEvaluatorTest {

    private final EvalGateEvaluator evaluator = new EvalGateEvaluator();

    @Test
    void shouldPassAbsoluteRelativeAndCriticalChecksAtBoundary() {
        EvalRun baseline = run("base", EvalType.INTENT, 0.95, 0.90, List.of(), binding("dataset-1", "model-1"), false);
        EvalRun current = run("current", EvalType.INTENT, 0.92, 0.88, List.of(), binding("dataset-1", "model-1"), false);
        EvalGateRule rule = rule(EvalType.INTENT, JudgeErrorPolicy.BLOCK, true,
            0.90, 0.85, 0.03, 0.02, List.of("critical-refund"));

        EvalGateCheckResult result = evaluator.evaluate(rule, current, baseline,
            candidate("model-1"));

        assertTrue(result.passed(), result.failures().toString());
    }

    @Test
    void shouldBlockArtifactMismatchCriticalCaseAndExcessiveRegression() {
        EvalRun baseline = run("base", EvalType.INTENT, 0.95, 0.90, List.of(), binding("dataset-1", "model-1"), false);
        EvalRun current = run("current", EvalType.INTENT, 0.80, 0.70,
            List.of("critical-refund"), binding("dataset-1", "model-1"), false);
        EvalGateRule rule = rule(EvalType.INTENT, JudgeErrorPolicy.BLOCK, true,
            0.90, null, 0.05, null, List.of("critical-refund"));

        EvalGateCheckResult result = evaluator.evaluate(rule, current, baseline,
            candidate("model-2"));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("候选不一致")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("超过允许值")));
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("零容忍")));
    }

    @Test
    void relativeRegressionShouldFailClosedWhenDatasetVersionChanged() {
        EvalRun baseline = run("base", EvalType.INTENT, 0.90, 0.80, List.of(), binding("dataset-old", "model-1"), false);
        EvalRun current = run("current", EvalType.INTENT, 0.95, 0.85, List.of(), binding("dataset-new", "model-1"), false);
        EvalGateRule rule = rule(EvalType.INTENT, JudgeErrorPolicy.BLOCK, false,
            null, null, 0.10, null, List.of());

        EvalGateCheckResult result = evaluator.evaluate(rule, current, baseline, candidate("model-1"));

        assertFalse(result.passed());
        assertTrue(result.failures().stream().anyMatch(value -> value.contains("数据集版本不同")));
    }

    @Test
    void judgeErrorPolicyShouldExplicitlyBlockOrAllowMetricChecks() {
        EvalRun errored = run("quality", EvalType.QUALITY, 0.0, 0.0,
            List.of("q1"), binding("dataset-1", "model-1"), true);
        EvalGateRule blocked = rule(EvalType.QUALITY, JudgeErrorPolicy.BLOCK, true,
            null, null, null, null, List.of());
        EvalGateRule allowed = rule(EvalType.QUALITY, JudgeErrorPolicy.ALLOW, true,
            0.99, 0.99, null, null, List.of());

        assertFalse(evaluator.evaluate(blocked, errored, null, candidate("model-1")).passed());
        EvalGateCheckResult allowedResult = evaluator.evaluate(
            allowed, errored, null, candidate("model-1"));
        assertTrue(allowedResult.passed(), allowedResult.failures().toString());
        assertTrue(allowedResult.notices().stream().anyMatch(value -> value.contains("ALLOW")));
    }

    private EvalGateRule rule(EvalType type, JudgeErrorPolicy errorPolicy, boolean match,
                              Double minPrimary, Double minSecondary,
                              Double maxPrimaryDrop, Double maxSecondaryDrop,
                              List<String> critical) {
        return new EvalGateRule(type, minPrimary, minSecondary, maxPrimaryDrop,
            maxSecondaryDrop, critical, errorPolicy, match);
    }

    private EvalRun run(String id, EvalType type, double primary, double secondary,
                        List<String> failedIds, EvalVersionBinding binding, boolean judgeError) {
        Map<String, Object> metrics = judgeError ? Map.of("status", "ERROR") : Map.of();
        return new EvalRun(id, type, 10, 10 - failedIds.size(), primary, secondary,
            failedIds, List.of(), metrics, EvalTrigger.MANUAL, 10, binding, null, 1L);
    }

    private EvalVersionBinding binding(String dataset, String model) {
        return new EvalVersionBinding(dataset, "hash-" + dataset, model, "prompt-1",
            "agent-1", "kb-1", "tool-1", "judge-1", "rubric-1");
    }

    private EvalVersionBinding candidate(String model) {
        return new EvalVersionBinding("", "", model, "prompt-1", "agent-1",
            "", "tool-1", "", "");
    }
}
