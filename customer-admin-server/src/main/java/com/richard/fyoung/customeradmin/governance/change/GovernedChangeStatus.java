package com.richard.fyoung.customeradmin.governance.change;

/** 双人复核变更状态机。 */
public enum GovernedChangeStatus {
    PENDING,
    EXECUTING,
    EXECUTED,
    REJECTED,
    FAILED,
    EXPIRED
}
