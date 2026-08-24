package com.richard.fyoung.customerwork.capability.eval;

/**
 * 对不可变数据集快照的命名与审核记录。
 *
 * <p>内容只存在 {@link EvalDatasetSnapshot}，本对象只增加人可检索的版本名和 maker-checker 审核事实。</p>
 */
public record EvalDatasetRelease(
    String releaseId,
    EvalType evalType,
    String versionName,
    String snapshotVersionId,
    String contentHash,
    int caseCount,
    EvalDatasetReviewStatus status,
    String reviewComment,
    Long createdBy,
    Long reviewedBy,
    long createdAtMs,
    Long reviewedAtMs
) {
}
