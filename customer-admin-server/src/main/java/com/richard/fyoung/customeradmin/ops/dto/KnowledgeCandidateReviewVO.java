package com.richard.fyoung.customeradmin.ops.dto;

import com.richard.fyoung.customerwork.capability.eval.EvalComparison;
import java.util.List;

/** 页面只展示候选身份、模型名称和逐题对照；不下发冻结语料库、系统提示词或模型端点。 */
public record KnowledgeCandidateReviewVO(String candidateId, long candidateRevision, long sourceReviewRevision,
    long agentId, String agentCode, Long modelDeploymentId, String modelName, Long judgeDeploymentId, String judgeModelName,
    String datasetReleaseId, String datasetVersionId, String targetCaseId, String artifactFingerprint,
    EvalComparison comparison, List<CaseReview> cases) {

    public record CaseReview(String caseId, String input, String expected, String baselineReply, String candidateReply,
                              boolean candidateRecalled, boolean baselineFailed, boolean candidateFailed) { }
}
