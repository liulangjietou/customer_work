package com.richard.fyoung.customeradmin.configversion.dto;

import java.util.List;

/** 安全回滚/灰度入队结果；PENDING 仅表示任务已可靠落库，不表示实例已生效。 */
public record ConfigPublishOperationResult(
    String operationId,
    String publishIntent,
    String status,
    Long sourceConfigVersionId,
    String sourceContentHash,
    List<PendingTask> tasks
) {

    /** 单个目标租户的可靠发布任务。 */
    public record PendingTask(String taskId, String tenantCode, Long targetId, String status) {
    }
}
