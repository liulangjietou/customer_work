package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;
import com.richard.fyoung.customerwork.capability.eval.QualityEvalStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 纯函数门禁判定器：绝对阈值、相对回归、关键 case、Judge ERROR 与候选版本匹配。 */
public class EvalGateEvaluator {

    private static final double EPSILON = 1e-9d;

    public EvalGateCheckResult evaluate(EvalGateRule rule,
                                        EvalRun current,
                                        EvalRun baseline,
                                        EvalVersionBinding candidate) {
        List<String> failures = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (current == null) {
            failures.add(rule.evalType() + " 缺少评测运行记录");
            return result(rule.evalType(), null, null, failures, notices);
        }

        EvalVersionBinding binding = current.versionBinding();
        if (binding == null || !binding.isComplete()) {
            failures.add(rule.evalType() + " 运行未绑定完整数据集/模型/提示词/Agent/知识/工具/Judge/rubric版本");
        }
        if (rule.requireArtifactMatch()
            && (binding == null || !binding.matchesCandidate(candidate))) {
            failures.add(rule.evalType() + " 运行版本与待发布候选不一致");
        }

        if (current.evalType() == EvalType.QUALITY
            && current.status() == QualityEvalStatus.ERROR) {
            if (rule.judgeErrorPolicy() == JudgeErrorPolicy.BLOCK) {
                failures.add("QUALITY Judge ERROR 按策略阻断");
            } else {
                notices.add("QUALITY Judge ERROR 已按 ALLOW 策略跳过该类指标检查");
                return result(rule.evalType(), current, baseline, failures, notices);
            }
        }

        requireMinimum("primaryMetric", current.primaryMetric(), rule.minPrimaryMetric(), failures);
        requireMinimum("secondaryMetric", current.secondaryMetric(), rule.minSecondaryMetric(), failures);
        requireRegression("primaryMetric", current.primaryMetric(), baseline,
            rule.maxPrimaryRegression(), true, binding, failures);
        requireRegression("secondaryMetric", current.secondaryMetric(), baseline,
            rule.maxSecondaryRegression(), false, binding, failures);

        Set<String> failedCases = new HashSet<>(current.failedCaseIds());
        for (String criticalCase : rule.criticalCaseIds()) {
            if (failedCases.contains(criticalCase)) {
                failures.add("关键用例失败（零容忍）：" + criticalCase);
            }
        }
        return result(rule.evalType(), current, baseline, failures, notices);
    }

    private void requireMinimum(String metric, double actual, Double minimum, List<String> failures) {
        if (minimum != null && actual + EPSILON < minimum) {
            failures.add(metric + "=" + actual + " 低于绝对阈值 " + minimum);
        }
    }

    private void requireRegression(String metric, double actual, EvalRun baseline,
                                   Double maxRegression, boolean primary,
                                   EvalVersionBinding binding, List<String> failures) {
        if (maxRegression == null) {
            return;
        }
        if (baseline == null) {
            failures.add(metric + " 已配置相对回归阈值，但缺少基线运行");
            return;
        }
        EvalVersionBinding baselineBinding = baseline.versionBinding();
        if (binding == null || baselineBinding == null
            || !Objects.equals(binding.datasetVersion(), baselineBinding.datasetVersion())) {
            failures.add(metric + " 的当前运行与基线数据集版本不同，禁止比较相对回归");
            return;
        }
        double baselineValue = primary ? baseline.primaryMetric() : baseline.secondaryMetric();
        double regression = baselineValue - actual;
        if (regression - maxRegression > EPSILON) {
            failures.add(metric + " 回归 " + regression + "，超过允许值 " + maxRegression);
        }
    }

    private EvalGateCheckResult result(EvalType type, EvalRun current, EvalRun baseline,
                                       List<String> failures, List<String> notices) {
        return new EvalGateCheckResult(type,
            current == null ? null : current.runId(),
            baseline == null ? null : baseline.runId(),
            failures.isEmpty(), failures, notices);
    }
}
