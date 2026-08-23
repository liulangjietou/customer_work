package com.richard.fyoung.customeradmin.aiconfig.secret.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/** 模型凭据轮换请求；secretValue 只在请求生命周期内存在。 */
public record SecretRotationRequest(
    @NotBlank(message = "secretValue 不能为空") String secretValue,
    LocalDateTime expiresAt) {
}
