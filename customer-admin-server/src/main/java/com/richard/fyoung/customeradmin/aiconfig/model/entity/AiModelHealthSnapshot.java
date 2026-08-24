package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型部署当前健康快照。 */
@Data
@TableName("ai_model_health_snapshot")
public class AiModelHealthSnapshot {

    @TableId
    private Long modelConfigId;
    private String tenantId;
    private String healthStatus;
    private String authStatus;
    private String capabilityStatus;
    private Integer consecutiveFailures;
    private Integer consecutiveSuccesses;
    private Long lastLatencyMs;
    private String lastErrorCategory;
    private String lastMessage;
    private LocalDateTime lastProbeAt;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    private LocalDateTime nextProbeAt;
    private LocalDateTime cooldownUntil;
    private String overrideMode;
    private String overrideReason;
    private Long overrideOperatorId;
    private String overrideOperatorName;
    private LocalDateTime overrideUntil;
    private Integer revision;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
