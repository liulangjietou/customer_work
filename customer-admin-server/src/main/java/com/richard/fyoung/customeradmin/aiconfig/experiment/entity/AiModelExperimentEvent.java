package com.richard.fyoung.customeradmin.aiconfig.experiment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 实验状态变化的追加式审计事件。 */
@Data
@TableName("ai_model_experiment_event")
public class AiModelExperimentEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long experimentId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private Long actorId;
    private LocalDateTime occurredAt;
    private LocalDateTime createTime;
}
