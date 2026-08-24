package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 单臂离线评测展示对象，不暴露凭据或数据集正文。 */
public record ModelExperimentArmEvalVO(
    Long id,
    String arm,
    Integer attemptNo,
    Long deploymentId,
    Integer endpointRevision,
    String datasetReleaseId,
    String datasetSnapshotVersionId,
    String datasetContentHash,
    Long judgeDeploymentId,
    Integer judgeEndpointRevision,
    String rubricVersion,
    String status,
    Integer total,
    Integer judged,
    Integer passed,
    BigDecimal avgScore,
    BigDecimal passRate,
    List<String> failedCaseIds,
    List<String> errorCaseIds,
    String errorMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {
}
