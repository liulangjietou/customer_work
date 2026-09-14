package com.richard.fyoung.customeradmin.ops.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import com.richard.fyoung.customerwork.capability.eval.EvalRun;
import java.util.List;
import java.util.Objects;

/** 同一冻结输入的对照组与候选组答复、评分和召回事实；不进入全局运行基线。 */
public record KnowledgeCandidateEvaluation(String tenantId, long improvementId, String artifactFingerprint,
                                            EvalRun baseline, EvalRun current, List<String> baselineReplies,
                                            List<String> candidateReplies, List<String> candidateRecalledCaseIds) {
    public KnowledgeCandidateEvaluation {
        baselineReplies = List.copyOf(baselineReplies);
        candidateReplies = List.copyOf(candidateReplies);
        candidateRecalledCaseIds = List.copyOf(candidateRecalledCaseIds);
    }

    /** 对比结论每次由两份运行事实计算，不接受持久化的独立通过标记。 */
    @JsonIgnore
    public EvalComparison comparison() {
        return EvalComparison.of(current, baseline);
    }

    /** 两组必须使用当前绑定的完整版本及同一批答复数量。 */
    public void requireMatches(KnowledgeCandidateBinding binding) {
        if (!Objects.equals(tenantId, binding.knowledge().tenantId())
            || improvementId != binding.improvementId() || !Objects.equals(artifactFingerprint, binding.fingerprint())
            || !Objects.equals(current.versionBinding(), binding.versions())
            || !Objects.equals(baseline.versionBinding(), binding.baselineVersions())
            || Objects.equals(current.runId(), baseline.runId())
            || baselineReplies.size() != binding.dataset().caseCount()
            || candidateReplies.size() != binding.dataset().caseCount()) {
            throw new IllegalStateException("knowledge evaluation does not match frozen binding");
        }
    }
}
