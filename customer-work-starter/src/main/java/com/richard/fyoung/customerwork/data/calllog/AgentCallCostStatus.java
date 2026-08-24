package com.richard.fyoung.customerwork.data.calllog;

/** 单次 Agent 调用汇总后的模型成本完整性。 */
public enum AgentCallCostStatus {
    /** 所有 MODEL 分段均已结算，且币种唯一。 */
    COMPLETE,
    /** 只结算了部分 MODEL 分段，金额仅代表已结算部分。 */
    PARTIAL,
    /** 没有任何可结算 MODEL 分段。 */
    UNAVAILABLE,
    /** 已结算分段包含多个币种，禁止直接相加为单一金额。 */
    MULTI_CURRENCY,
    /** 本次调用没有 MODEL 分段。 */
    NO_MODEL
}
