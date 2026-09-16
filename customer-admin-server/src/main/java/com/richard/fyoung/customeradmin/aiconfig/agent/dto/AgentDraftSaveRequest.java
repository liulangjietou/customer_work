package com.richard.fyoung.customeradmin.aiconfig.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 草稿允许缺少名称、模型等必填项，故 configuration 不递归执行正式保存的 @Valid。 */
public record AgentDraftSaveRequest(
    @NotNull @PositiveOrZero Long expectedVersion,
    @Positive Long agentId,
    @PositiveOrZero Long baseRevision,
    @NotNull AgentSaveRequest configuration) {
}
