package com.richard.fyoung.customeradmin.aiconfig.experiment.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 在线实验定义与当前生命周期视图，不包含模型凭据或分桶盐。 */
@Data
public class ModelExperimentVO {

    private Long id;
    private String experimentCode;
    private String experimentName;
    private Long agentId;
    private Long controlDeploymentId;
    private String controlModelRef;
    private Integer controlEndpointRevision;
    private Long treatmentDeploymentId;
    private String treatmentModelRef;
    private Integer treatmentEndpointRevision;
    private Integer revision;
    private Integer treatmentBps;
    private String status;
    private String effectiveState;
    private String activationTaskId;
    private String activationTaskStatus;
    private String activationTaskGateStatus;
    private String deactivationTaskId;
    private String deactivationTaskStatus;
    private String deactivationTaskGateStatus;
    private String effectiveTaskId;
    private String effectiveTaskStatus;
    private String effectiveTaskGateStatus;
    private String effectiveTaskLastError;
    private Long minSample;
    private BigDecimal maxErrorRate;
    private Long maxP95LatencyMs;
    private LocalDateTime expiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;
    private LocalDateTime completedAt;
    private String stopReason;
    private Long createBy;
    private LocalDateTime createTime;
}
