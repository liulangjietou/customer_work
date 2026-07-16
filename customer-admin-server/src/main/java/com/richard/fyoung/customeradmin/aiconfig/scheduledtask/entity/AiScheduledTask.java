package com.richard.fyoung.customeradmin.aiconfig.scheduledtask.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 定时任务定义：绑定一个智能体 + 一段触发时传给它的 prompt。
 *
 * <p>{@code cron} 落本表：internal 调度模式下由内置动态调度器（{@code ScheduledTaskScheduler}）
 * 按此周期执行；xxl-job 模式下 cron 仅保存展示，周期以 XXL-JOB 控制台配置为准。cron 为空则不参与
 * 内置周期调度。{@code enabled} 控制"该任务是否允许被执行"，disabled 时任何触发都 fast-fail 拒绝。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_scheduled_task")
public class AiScheduledTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编码：= XXL-JOB 触发时执行器参数里携带的业务标识，全局唯一。 */
    private String taskCode;
    private String taskName;
    /** 关联智能体ID（逻辑关联 ai_agent.id）。 */
    private Long agentId;
    /** 每次触发时传给 Agent 的用户消息内容。 */
    private String prompt;
    /** cron 表达式（Spring 6 位；可空）：internal 模式内置调度器按此周期执行，xxl-job 模式仅展示。 */
    private String cron;
    /** 0禁用 / 1启用。 */
    private Integer enabled;
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
