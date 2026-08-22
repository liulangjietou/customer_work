package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

/** 路由规则视图，只带部署元数据，不带端点凭据。 */
@Data
public class ModelRouteRuleVO {
    private Long id;
    private String purpose;
    private Long deploymentId;
    private String deploymentCode;
    private String deploymentName;
    private Integer priority;
    private ModelRouteCondition condition;
    private String conditionSummary;
}
