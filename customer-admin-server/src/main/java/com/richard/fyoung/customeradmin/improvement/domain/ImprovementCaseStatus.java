package com.richard.fyoung.customeradmin.improvement.domain;

/** 从责任认领到线上效果确认的单一状态机。 */
public enum ImprovementCaseStatus {
    OWNED,
    READY_FOR_REEVALUATION,
    REEVALUATING,
    REEVALUATION_FAILED,
    READY_TO_PUBLISH,
    PUBLISHING,
    PUBLISH_FAILED,
    OBSERVING,
    VERIFIED,
    INEFFECTIVE,
    INCONCLUSIVE,
    CANCELLED;

    public boolean terminal() {
        return this == VERIFIED || this == INEFFECTIVE
            || this == INCONCLUSIVE || this == CANCELLED;
    }
}
