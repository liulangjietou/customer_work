package com.richard.fyoung.customeradmin.governance.change.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 复核结论，理由是审计事实的一部分。 */
public record GovernanceDecisionRequest(
    @NotBlank(message = "复核理由不能为空")
    @Size(max = 500, message = "复核理由不能超过500个字符")
    String reason) {
}
