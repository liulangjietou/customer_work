package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 路由策略身份及当前版本摘要。 */
@Data
public class ModelRoutePolicyVO {
    private Long id;
    private String policyCode;
    private String policyName;
    private String description;
    private String status;
    private Integer currentVersionNo;
    private Integer latestVersionNo;
    private ModelRouteVersionVO currentVersion;
    private LocalDateTime updateTime;
}
