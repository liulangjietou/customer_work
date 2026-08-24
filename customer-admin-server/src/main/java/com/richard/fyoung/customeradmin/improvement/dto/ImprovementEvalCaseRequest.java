package com.richard.fyoung.customeradmin.improvement.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 从原始问题创建一条可复评的回归用例。 */
public record ImprovementEvalCaseRequest(
    @NotBlank String caseId,
    @NotNull EvalType evalType,
    String expected,
    String category
) {
}
