package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 人工停止必须给出可审计原因。 */
public record ModelExperimentStopRequest(
    @NotBlank @Size(max = 500) String reason
) {
}
