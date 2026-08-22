package com.richard.fyoung.customeradmin.aiconfig.model.dto;

import lombok.Data;

import java.util.List;

/** 路由 dry-run 结果；强制备用缺失时 matched=false 且 failClosed=true。 */
@Data
public class ModelRouteDryRunVO {
    private Long policyId;
    private Integer versionNo;
    private String contentHash;
    private boolean matched;
    private boolean failClosed;
    private Long deploymentId;
    private String deploymentCode;
    private String deploymentName;
    private String purpose;
    private Integer priority;
    private String explanation;
    private List<ModelRouteCandidateExplanationVO> candidates;
}
