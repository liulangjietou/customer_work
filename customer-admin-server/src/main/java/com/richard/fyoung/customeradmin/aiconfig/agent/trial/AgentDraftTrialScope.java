package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

/** tenantId/ownerId 只能从可信登录上下文派生；草稿和试用标识均属于该个人范围。 */
public record AgentDraftTrialScope(String tenantId, long ownerId, String draftId, String trialId) {
}
