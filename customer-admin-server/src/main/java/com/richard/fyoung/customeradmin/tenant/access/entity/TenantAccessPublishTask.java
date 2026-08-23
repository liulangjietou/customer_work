package com.richard.fyoung.customeradmin.tenant.access.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 租户访问快照可靠发布任务。 */
@Data
@TableName("sys_tenant_access_publish_task")
public class TenantAccessPublishTask {

    @TableId
    private String id;
    private Long seq;
    private String tenantId;
    private String tenantStatus;
    private Long accessEpoch;
    private String operation;
    private String sessionRevocationStatus;
    private String channelDisableStatus;
    private Integer channelsDisabledCount;
    private LocalDateTime expireTime;
    private String dataId;
    private String groupName;
    private String status;
    private Integer attempts;
    private Long nextAttemptAtMs;
    private String activeLeaseKey;
    private String leaseOwner;
    private Long leaseUntilMs;
    private String lastError;
    private Long publishedAtMs;
    private Long createdAtMs;
    private Long updatedAtMs;
}
