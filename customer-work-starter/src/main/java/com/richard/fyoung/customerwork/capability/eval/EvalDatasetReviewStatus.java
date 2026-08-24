package com.richard.fyoung.customerwork.capability.eval;

/** 命名评测集版本的审核状态；审核结论是终态，不允许反复改写历史。 */
public enum EvalDatasetReviewStatus {
    DRAFT,
    APPROVED,
    REJECTED
}
