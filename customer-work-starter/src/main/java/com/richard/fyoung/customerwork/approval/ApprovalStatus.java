package com.richard.fyoung.customerwork.approval;

/**
 * 审批单状态机：PENDING →（approve）APPROVED ／（deny）DENIED，终态不可再变。
 * @author owlzhangfq@gmail.com
 */
public enum ApprovalStatus {

    /** 待人工决策。 */
    PENDING,

    /** 已人工放行（下游可执行实际动作，如打款）。 */
    APPROVED,

    /** 已人工拒绝。 */
    DENIED
}
