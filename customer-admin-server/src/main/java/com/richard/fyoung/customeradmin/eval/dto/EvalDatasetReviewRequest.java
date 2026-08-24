package com.richard.fyoung.customeradmin.eval.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;

/** 审核只接受 APPROVED 或 REJECTED；DRAFT 不是审核动作。 */
public record EvalDatasetReviewRequest(
    @NotNull EvalDatasetReviewStatus decision,
    @Size(max = 500) String comment
) {
}
