package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 模型部署认证视图，effectiveStatus 会计算过期与配置漂移。 */
@Data
public class ModelCertificationVO {
    private Long runId;
    private String status;
    private String effectiveStatus;
    private String staleReason;
    private Integer certifiedEndpointRevision;
    private Integer certifiedSecretVersion;
    private LocalDateTime validUntil;
    private LocalDateTime completedAt;
    private Integer passedChecks;
    private Integer failedChecks;
    private Long latencyP95Ms;
    private Integer verifiedContextTokens;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private String currency;
    private String failureCode;
    private String failureMessage;
    private List<ModelCertificationCheckVO> checks;
}
