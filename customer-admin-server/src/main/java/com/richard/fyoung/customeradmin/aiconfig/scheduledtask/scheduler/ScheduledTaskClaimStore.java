package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler;

import java.time.Instant;

/** 内置 cron 的全局触发认领；同一任务同一计划触发时刻只允许一个 Pod 成功。 */
@FunctionalInterface
public interface ScheduledTaskClaimStore {

    boolean claim(String tenantId, Long taskId, String taskCode, Instant scheduledFireTime);
}
