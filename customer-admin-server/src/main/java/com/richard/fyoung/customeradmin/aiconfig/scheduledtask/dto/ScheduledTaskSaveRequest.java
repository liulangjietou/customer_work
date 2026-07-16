package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 定时任务新建/编辑请求。{@code taskCode} 须全局唯一、{@code agentId} 须指向已启用的智能体
 * （校验见 {@code ScheduledTaskService}）。
 * @author owlzhangfq@gmail.com
 */
public record ScheduledTaskSaveRequest(
    @NotBlank(message = "taskCode 不能为空") String taskCode,
    @NotBlank(message = "taskName 不能为空") String taskName,
    @NotNull(message = "agentId 不能为空") Long agentId,
    @NotBlank(message = "prompt 不能为空") String prompt,
    /** cron 表达式（Spring 6 位，可空）：internal 调度模式下按此周期执行；格式非法由 Service 层 fast-fail。 */
    String cron,
    Boolean enabled,
    String remark) {
}
