package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate;

import com.richard.fyoung.customerwork.capability.eval.EvalType;

import java.util.List;

/** 一类评测的发布门禁规则。所有指标均为 0-1 归一化口径。 */
public record EvalGateRule(
    EvalType evalType,
    Double minPrimaryMetric,
    Double minSecondaryMetric,
    Double maxPrimaryRegression,
    Double maxSecondaryRegression,
    List<String> criticalCaseIds,
    JudgeErrorPolicy judgeErrorPolicy,
    boolean requireArtifactMatch
) {

    public EvalGateRule {
        criticalCaseIds = criticalCaseIds == null ? List.of() : List.copyOf(criticalCaseIds);
        judgeErrorPolicy = judgeErrorPolicy == null ? JudgeErrorPolicy.BLOCK : judgeErrorPolicy;
    }
}
