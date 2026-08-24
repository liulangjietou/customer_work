package com.richard.fyoung.customeradmin.aiconfig.experiment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** control/treatment 单臂离线评测事实；每次尝试只追加，不覆盖历史。 */
@Data
@TableName("ai_model_experiment_arm_eval")
public class AiModelExperimentArmEval {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long experimentId;
    private String arm;
    private Integer attemptNo;
    private Long deploymentId;
    private Integer endpointRevision;
    private String datasetReleaseId;
    private String datasetSnapshotVersionId;
    private String datasetContentHash;
    private Long judgeDeploymentId;
    private Integer judgeEndpointRevision;
    private String rubricVersion;
    private String status;
    private Integer total;
    private Integer judged;
    private Integer passed;
    private BigDecimal avgScore;
    private BigDecimal passRate;
    private String failedCaseIdsJson;
    private String errorCaseIdsJson;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
}
