package com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 运行时配置可靠发布任务。 */
@Data
@TableName("ai_runtime_publish_task")
public class RuntimePublishTask {

    @TableId
    private String id;
    private Long seq;
    private String tenantId;
    private String targetCode;
    private Long targetId;
    private Long experimentId;
    private String experimentPublishAction;
    private String operationId;
    private String publishIntent;
    private Long sourceConfigVersionId;
    private String sourceContentHash;
    private String rollbackPatchJson;
    private String channelCode;
    private String dataId;
    private String groupName;
    private String revision;
    private String contentHash;
    private String publishScope;
    private String grayTenants;
    private Integer sourceVersion;
    private String remark;
    private String status;
    private Integer attempts;
    private Long nextAttemptAtMs;
    private String leaseOwner;
    private Long leaseUntilMs;
    private String lastError;
    private String candidateVersionsJson;
    private String gateStatus;
    private String gateEvalRunIdsJson;
    private String gateDecisionJson;
    private Long gateEvaluatedAtMs;
    private Long gateOverrideId;
    private Long createdAtMs;
    private Long updatedAtMs;
}
