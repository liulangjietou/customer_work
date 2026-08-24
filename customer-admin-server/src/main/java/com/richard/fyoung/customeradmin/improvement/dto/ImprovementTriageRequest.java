package com.richard.fyoung.customeradmin.improvement.dto;

import jakarta.validation.constraints.NotNull;

/** 认领原始改进信号；owner 为空时由控制器取当前登录人。 */
public record ImprovementTriageRequest(
    String ownerId,
    @NotNull Long slaDueAtMs
) {
}
