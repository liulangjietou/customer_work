package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

/** 单次交互试用的边界；超时会取消当前调用，恢复查询不会重新执行模型。 */
public final class AgentDraftTrialLimits {
    public static final int MAX_INPUT_CHARS = 4000;
    public static final long EXECUTION_TIMEOUT_MILLIS = 120_000L;
    public static final int HISTORY_LIMIT = 20;

    private AgentDraftTrialLimits() {
    }
}
