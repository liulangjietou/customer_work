package com.richard.fyoung.customeradmin.improvement.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalType;
import jakarta.validation.constraints.NotNull;

/** 绑定待复评的精确 Agent 运行候选与目标回归用例。 */
public record ImprovementBindArtifactRequest(
    @NotNull Long agentId,
    @NotNull EvalType evalType,
    String evalCaseId
) {
}
