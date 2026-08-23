package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 创建不可变双臂实验定义；revision 与 salt 由服务端生成。 */
public record ModelExperimentCreateRequest(
    @NotBlank @Size(max = 128) String experimentName,
    @NotNull Long agentId,
    @NotNull Long controlDeploymentId,
    @NotNull Long treatmentDeploymentId,
    @NotNull @Min(1) @Max(9999) Integer treatmentBps,
    @NotNull @Min(1) Long minSample,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal maxErrorRate,
    @NotNull @Min(1) Long maxP95LatencyMs,
    @NotNull @Future LocalDateTime expiresAt
) {
}
