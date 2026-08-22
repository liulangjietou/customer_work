package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.dto;

import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.JudgeErrorPolicy;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 保存某类评测发布门禁策略。null 阈值表示不启用该项检查。 */
public record EvalGatePolicyRequest(
    Boolean enabled,
    @DecimalMin("0.0") @DecimalMax("1.0") Double minPrimaryMetric,
    @DecimalMin("0.0") @DecimalMax("1.0") Double minSecondaryMetric,
    @DecimalMin("0.0") @DecimalMax("1.0") Double maxPrimaryRegression,
    @DecimalMin("0.0") @DecimalMax("1.0") Double maxSecondaryRegression,
    @Size(max = 200) List<@NotBlank @Size(max = 64) String> criticalCaseIds,
    JudgeErrorPolicy judgeErrorPolicy,
    Boolean requireArtifactMatch
) {
}
