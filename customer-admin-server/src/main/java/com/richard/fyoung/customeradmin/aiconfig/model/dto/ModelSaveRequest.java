package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 模型新建/编辑请求。新建时 {@code apiKey} 必填；编辑时可不传（留空=不改 AppKey，
 * 避免每次编辑都要求重新输入明文密钥）。
 * @author owlzhangfq@gmail.com
 */
public record ModelSaveRequest(
    @NotBlank(message = "modelName 不能为空") String modelName,
    String provider,
    String apiKey,
    @NotBlank(message = "baseUrl 不能为空") String baseUrl,
    @NotBlank(message = "model 不能为空") String model,
    BigDecimal temperature,
    Integer maxTokens,
    Boolean isDefault,
    Integer status) {
}
