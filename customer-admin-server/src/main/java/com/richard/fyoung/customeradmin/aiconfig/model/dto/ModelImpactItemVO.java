package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

/** 模型影响图的一条资源边投影。 */
@Data
public class ModelImpactItemVO {
    private String tenantId;
    private String resourceType;
    private String relationType;
    private String resourceId;
    private String resourceCode;
    private String resourceName;
    private String status;
    private Boolean blocking;
}
