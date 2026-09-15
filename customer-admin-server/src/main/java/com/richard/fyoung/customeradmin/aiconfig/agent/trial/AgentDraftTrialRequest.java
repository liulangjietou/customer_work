package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 请求标识在路径中固定；实际配置只从所属个人草稿读取。 */
public record AgentDraftTrialRequest(
    @Positive long expectedDraftVersion,
    @NotBlank @Size(max = AgentDraftTrialLimits.MAX_INPUT_CHARS) String input
) {
}
