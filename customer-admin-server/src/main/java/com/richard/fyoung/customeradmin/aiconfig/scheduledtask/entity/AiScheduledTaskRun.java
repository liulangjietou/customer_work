package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务执行历史（只追加日志，不做逻辑删除/审计人字段）。
 *
 * <p>手动触发（MANUAL）与 XXL-JOB 触发（XXL_JOB）都落一条；成功/失败都落——失败也要留证，
 * 便于运营排查"为什么这次没执行成功"。{@code output} 落库前由 Service 层截断到 8000 字符。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_scheduled_task_run")
public class AiScheduledTaskRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    /** 冗余任务编码：任务被删除后历史记录仍可读。 */
    private String taskCode;
    /** 触发方式：XXL_JOB / MANUAL。 */
    private String triggerType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long costMs;
    /** 执行结果：SUCCESS / FAILED。 */
    private String status;
    private String output;
    private String errorMessage;
}
