package com.richard.fyoung.customeradmin.ops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 浏览器只确认已审阅的候选和运行；正文、租户与操作人仍从可信存储及登录态取得。 */
public record KnowledgeCandidatePublishRequest(
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedArtifactFingerprint,
    @NotBlank @Size(max = 64) String expectedEvaluationRunId
) { }
