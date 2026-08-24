package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthOverrideMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 人工覆盖模型路由健康状态。AUTO 表示清除覆盖，强制模式由服务端校验有效期。 */
public record ModelHealthOverrideRequest(
    @NotNull ModelHealthOverrideMode mode,
    @NotBlank @Size(max = 500) String reason,
    LocalDateTime expiresAt
) {
}
