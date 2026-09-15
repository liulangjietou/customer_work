package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

/** 持久回执的稳定错误码；不将厂商异常、密钥或调用原文直接返回浏览器。 */
public enum AgentDraftTrialError {
    TRIAL_RESULT_UNKNOWN,
    TRIAL_TIMEOUT,
    TRIAL_TOOL_RESTRICTED,
    TRIAL_QUOTA_EXCEEDED,
    TRIAL_EXECUTION_FAILED
}
