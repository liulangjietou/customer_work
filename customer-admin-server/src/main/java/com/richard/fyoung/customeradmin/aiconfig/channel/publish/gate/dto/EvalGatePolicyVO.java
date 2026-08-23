package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.JudgeErrorPolicy;
import com.richard.fyoung.customerwork.capability.eval.EvalType;

import java.time.LocalDateTime;
import java.util.List;

/** 门禁策略展示对象。 */
public record EvalGatePolicyVO(
    EvalType evalType,
    boolean enabled,
    Double minPrimaryMetric,
    Double minSecondaryMetric,
    Double maxPrimaryRegression,
    Double maxSecondaryRegression,
    List<String> criticalCaseIds,
    JudgeErrorPolicy judgeErrorPolicy,
    boolean requireArtifactMatch,
    LocalDateTime updateTime
) {
}
