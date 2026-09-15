package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

/** 试用回执的持久状态；回答成功不等同于评测或发布门禁通过。 */
public enum AgentDraftTrialPhase {
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN;

    /** 只有运行中记录可以接收一次终态。 */
    public boolean terminal() {
        return this != RUNNING;
    }
}
