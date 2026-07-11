package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行历史视图对象。
 * @author owlzhangfq@gmail.com
 */
@Data
public class ScheduledTaskRunVO {
    private Long id;
    private Long taskId;
    private String taskCode;
    /** XXL_JOB / MANUAL。 */
    private String triggerType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long costMs;
    /** SUCCESS / FAILED。 */
    private String status;
    private String output;
    private String errorMessage;
}
