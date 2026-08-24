package com.richard.fyoung.customeradmin.workspace.vibecoding.store;

/** Plan/HITL 挂起记录的唯一终态机。 */
public enum PlanConfirmationState {
    PENDING,
    APPROVED,
    REJECTED,
    TIMEOUT,
    CANCELLED;

    public boolean terminal() {
        return this != PENDING;
    }

    public boolean approved() {
        return this == APPROVED;
    }
}
