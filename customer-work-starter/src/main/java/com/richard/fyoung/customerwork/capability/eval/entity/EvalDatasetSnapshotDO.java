package com.richard.fyoung.customerwork.capability.eval.entity;

import lombok.Data;

/** {@code cw_eval_dataset_version} 的只插入持久化数据袋。 */
@Data
public class EvalDatasetSnapshotDO {

    private String versionId;
    private String evalType;
    private String contentHash;
    private Integer caseCount;
    private String casesJson;
    private Long createdAtMs;
}
