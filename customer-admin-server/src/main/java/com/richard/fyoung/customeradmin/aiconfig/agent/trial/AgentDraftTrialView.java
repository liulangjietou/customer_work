package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import java.util.List;

/** 浏览器只得到运行事实与可读范围，不返回原始冻结快照、凭据或其它个人草稿。 */
public record AgentDraftTrialView(String id, long draftVersion, String input, String configurationFingerprint,
                                 AgentDraftTrialMatch configurationMatch, AgentDraftTrialPhase phase, String errorCode,
                                 long acceptedAtMs, long deadlineAtMs, Long finishedAtMs,
                                 AgentDraftTrialRunner.TrialResult result, List<String> restrictions) { }
