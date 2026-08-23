package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型部署最近认证快照；有效状态还需结合端点、凭据版本和有效期计算。 */
@Data
@TableName("ai_model_certification")
public class AiModelCertification {
    @TableId(type = IdType.INPUT)
    private Long modelConfigId;
    private String tenantId;
    private String status;
    private Long currentRunId;
    private Integer certifiedEndpointRevision;
    private Integer certifiedSecretVersion;
    private LocalDateTime validUntil;
    private LocalDateTime completedAt;
    private Integer passedChecks;
    private Integer failedChecks;
    private Long latencyP95Ms;
    private Integer verifiedContextTokens;
    private String failureCode;
    private String failureMessage;
    private Integer revision;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
