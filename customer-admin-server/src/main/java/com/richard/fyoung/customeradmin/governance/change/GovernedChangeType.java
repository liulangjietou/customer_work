package com.richard.fyoung.customeradmin.governance.change;

/** 必须经过 maker-checker 的高风险变更类型。 */
public enum GovernedChangeType {
    CONFIG_ROLLBACK,
    CONFIG_GRAY_RELEASE
}
