package com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 发布门禁紧急豁免审计事实；每个任务最多一条，只追加。 */
@Data
@TableName("ai_eval_release_gate_override")
public class EvalGateOverrideEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String taskId;
    private String candidateContentHash;
    private Long operatorId;
    private String reason;
    private String previousDecisionJson;
    private LocalDateTime createdAt;
}
