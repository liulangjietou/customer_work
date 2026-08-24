package com.richard.fyoung.customeradmin.aiconfig.experiment.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 双臂在线模型实验定义；创建后不提供通用编辑接口。 */
@Data
@TableName("ai_model_experiment")
public class AiModelExperiment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String experimentCode;
    private String experimentName;
    private Long agentId;
    private Long controlDeploymentId;
    private String controlModelRef;
    private Integer controlEndpointRevision;
    private Long treatmentDeploymentId;
    private String treatmentModelRef;
    private Integer treatmentEndpointRevision;
    private String datasetReleaseId;
    private String datasetVersionName;
    private String datasetSnapshotVersionId;
    private String datasetContentHash;
    private Long judgeDeploymentId;
    private String judgeModelRef;
    private Integer judgeEndpointRevision;
    private String offlineEvalStatus;
    private LocalDateTime offlineEvalStartedAt;
    private LocalDateTime offlineEvalCompletedAt;
    private String offlineEvalError;
    private Integer revision;
    private String assignmentSalt;
    private Integer treatmentBps;
    private String status;
    private String activationTaskId;
    private String deactivationTaskId;
    private Long minSample;
    private BigDecimal maxErrorRate;
    private Long maxP95LatencyMs;
    private LocalDateTime expiresAt;
    private LocalDateTime startedAt;
    private LocalDateTime stoppedAt;
    private LocalDateTime completedAt;
    private String stopReason;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
