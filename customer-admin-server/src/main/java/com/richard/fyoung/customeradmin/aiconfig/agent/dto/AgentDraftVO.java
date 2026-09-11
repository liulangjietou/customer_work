package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

/** 列表仅返回概要；只有按归属人读取详情时才返回配置内容。 */
public record AgentDraftVO(String id, Long agentId, Long baseRevision, String title,
                           long version, long updatedAtMs, AgentSaveRequest configuration) {
}
