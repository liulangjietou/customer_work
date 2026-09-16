package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

/** 最近试用仅返回输入摘要与状态，不批量传输冻结的资源内容和完整回答。 */
public record AgentDraftTrialSummary(String id, long draftVersion, String inputExcerpt,
                                    String configurationFingerprint, AgentDraftTrialPhase phase,
                                    String errorCode, long acceptedAtMs, Long finishedAtMs) {
}
