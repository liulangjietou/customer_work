package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** 上线认证门槛；未要求的能力仍写入 SKIPPED 证据，不会从报告中消失。 */
public record ModelCertificationRequest(
    @NotNull(message = "requiredContextTokens 不能为空")
    @Min(value = 1, message = "requiredContextTokens 必须大于 0")
    @Max(value = 2000000, message = "requiredContextTokens 不能超过 2000000") Integer requiredContextTokens,
    @NotNull(message = "maxLatencyMs 不能为空")
    @Min(value = 1, message = "maxLatencyMs 必须大于 0") Long maxLatencyMs,
    @NotNull(message = "maxInputPrice 不能为空")
    @DecimalMin(value = "0", message = "maxInputPrice 不能小于 0") BigDecimal maxInputPrice,
    @NotNull(message = "maxOutputPrice 不能为空")
    @DecimalMin(value = "0", message = "maxOutputPrice 不能小于 0") BigDecimal maxOutputPrice,
    @NotNull(message = "validDays 不能为空")
    @Min(value = 1, message = "validDays 必须大于 0")
    @Max(value = 365, message = "validDays 不能超过 365") Integer validDays,
    Boolean requireStreaming,
    Boolean requireToolCall,
    Boolean requireStructuredOutput) {

    public boolean streamingRequired() {
        return !Boolean.FALSE.equals(requireStreaming);
    }

    public boolean toolCallRequired() {
        return !Boolean.FALSE.equals(requireToolCall);
    }

    public boolean structuredOutputRequired() {
        return !Boolean.FALSE.equals(requireStructuredOutput);
    }
}
