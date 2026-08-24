package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 文档源新建/编辑请求。当前首个可执行适配器为 PUSH。 */
public record KnowledgeSourceSaveRequest(
    @NotBlank(message = "sourceCode 不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$",
        message = "sourceCode 仅支持字母、数字、点、下划线和短横线")
    String sourceCode,
    @NotBlank(message = "sourceName 不能为空")
    @Size(max = 128, message = "sourceName 不能超过 128 字符") String sourceName,
    String sourceType,
    Integer status,
    @Min(value = 1, message = "freshnessSlaMinutes 必须大于 0")
    @Max(value = 525600, message = "freshnessSlaMinutes 不能超过一年")
    Integer freshnessSlaMinutes,
    @DecimalMin(value = "0.0000", message = "qualityThreshold 不能小于 0")
    @DecimalMax(value = "1.0000", message = "qualityThreshold 不能大于 1")
    BigDecimal qualityThreshold,
    KnowledgeAclRequest defaultAcl) {
}
