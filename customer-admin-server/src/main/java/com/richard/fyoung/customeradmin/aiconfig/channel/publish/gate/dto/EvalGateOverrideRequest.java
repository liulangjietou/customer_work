package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 紧急豁免必须提供可审计原因。 */
public record EvalGateOverrideRequest(
    @NotBlank(message = "豁免原因不能为空")
    @Size(max = 500, message = "豁免原因不能超过500字")
    String reason
) {
}
