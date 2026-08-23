package com.richard.fyoung.customeradmin.tenant.access.dto;

import lombok.Data;

/** 控制面查看租户访问快照投递状态。 */
@Data
public class TenantAccessDeliveryVO {

    private String taskId;
    private String tenantId;
    private String tenantStatus;
    private Long accessEpoch;
    private String operation;
    private String orchestrationStatus;
    private String sessionRevocationStatus;
    private String channelDisableStatus;
    private Integer channelsDisabledCount;
    private String dataId;
    private String status;
    private Integer attempts;
    private String lastError;
    private Long createdAtMs;
    private Long updatedAtMs;
    private Long publishedAtMs;
}
