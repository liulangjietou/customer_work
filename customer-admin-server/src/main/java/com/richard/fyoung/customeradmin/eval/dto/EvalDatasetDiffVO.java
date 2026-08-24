package com.richard.fyoung.customeradmin.eval.dto;

import java.util.List;

/** 两个命名版本的结构化差异。 */
public record EvalDatasetDiffVO(
    String fromReleaseId,
    String toReleaseId,
    List<String> addedCaseIds,
    List<String> removedCaseIds,
    List<EvalDatasetCaseDiff> changedCases
) {
}
