package com.richard.fyoung.customeradmin.improvement.dto;

import com.richard.fyoung.customeradmin.improvement.domain.ImprovementCaseStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementEffectStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementReevaluationStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSlaStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementSourceType;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.capability.eval.EvalVersionBinding;

/** 改进闭环对外视图；租约等内部协调字段不下发。 */
public record ImprovementCaseVO(
    Long id,
    ImprovementSourceType sourceType,
    String sourceKey,
    long sourceSignalCount,
    String ownerId,
    long slaDueAtMs,
    ImprovementSlaStatus slaStatus,
    long overdueMs,
    ImprovementCaseStatus status,
    Long agentId,
    String agentCode,
    String artifactType,
    String artifactVersion,
    EvalVersionBinding candidateVersions,
    EvalType evalType,
    String evalCaseId,
    String evalRunId,
    ImprovementReevaluationStatus reevaluationStatus,
    String reevaluationVerdict,
    String reevaluationError,
    String publishTaskId,
    String publishRevision,
    String publishStatus,
    Long publishedAtMs,
    Long observationStartedAtMs,
    Long observationEndsAtMs,
    Integer minExposureCalls,
    Integer maxRecurrenceSignals,
    long observedCalls,
    long observedSignals,
    ImprovementEffectStatus effectStatus,
    Long lastObservedAtMs,
    String lastError,
    long createdAtMs,
    long updatedAtMs
) {
}
