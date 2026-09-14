package com.richard.fyoung.customeradmin.ops.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 操作人选择待测候选、模型和审核后的用例集；完整快照及版本指纹只由服务端生成。 */
public record KnowledgeCandidateBindRequest(
    @NotBlank @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String candidateId,
    @Min(1) long candidateRevision,
    @NotNull @Min(1) Long agentId,
    @NotNull @Min(1) Long modelDeploymentId,
    @NotNull @Min(1) Long judgeDeploymentId,
    @NotBlank @Size(max = 64) String datasetReleaseId,
    @NotBlank @Size(max = 128) String targetCaseId
) { }
