package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.scheduler;

/**
 * 定时任务变更事件：{@code ScheduledTaskService} 在增删改/启停后发布，
 * {@link ScheduledTaskScheduler} 监听后动态重注册/取消对应任务的内置调度。
 *
 * <p>用事件解耦而非直接方法调用——Service 只依赖 {@code ApplicationEventPublisher}（框架 Bean），
 * 不反向依赖调度器，避免 Service ↔ Scheduler 构造循环。</p>
 *
 * @param taskId  发生变更的任务ID
 * @param removed 是否为删除（true 仅取消调度；false 重新加载并按 enabled + cron 决定是否注册）
 * @author owlzhangfq@gmail.com
 */
public record ScheduledTaskChangedEvent(Long taskId, boolean removed) {
}
