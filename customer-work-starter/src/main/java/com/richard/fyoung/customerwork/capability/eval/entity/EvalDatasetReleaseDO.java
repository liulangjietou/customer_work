package com.richard.fyoung.customerwork.capability.eval.entity;

import lombok.Data;

/** {@code cw_eval_dataset_release} 的持久化数据袋。 */
@Data
public class EvalDatasetReleaseDO {

    private String releaseId;
    private String evalType;
    private String versionName;
    private String snapshotVersionId;
    private String contentHash;
    private Integer caseCount;
    private String status;
    private String reviewComment;
    private Long createdBy;
    private Long reviewedBy;
    private Long createdAtMs;
    private Long reviewedAtMs;
}
