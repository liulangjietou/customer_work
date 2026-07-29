package com.richard.fyoung.customeradmin.workspace.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 智能体后台委派任务（{@code agent_spawn} 传 {@code timeout_seconds=0} 时产生的异步任务）。
 *
 * <p>贫血 DO，只承载 {@code ai_agent_task} 表的字段；状态流转与执行编排在
 * {@code MybatisTaskRepository} 里，查询与取消在 {@code AgentTaskService} 里。</p>
 *
 * <p>{@code taskId} 是框架侧的任务标识（{@code agent_spawn} 返回给模型的那个），与自增主键
 * {@code id} 分开：框架只认 {@code taskId}，管理台分页/排序用 {@code id} 更稳。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_task")
public class AiAgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 框架任务 ID，全局唯一。 */
    private String taskId;
    /** 发起任务的父智能体编码。 */
    private String parentAgentCode;
    /** 执行任务的子智能体标识。 */
    private String subAgentId;
    /** 父会话 ID（任务归属的那次对话）。 */
    private String parentSessionId;
    /** 状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED。 */
    private String status;
    /** 成功时的结果文本。 */
    private String result;
    /** 失败时的错误信息。 */
    private String errorMessage;
    /** 是否已请求取消：取消请求与实际终态分开记，便于区分"用户点了取消"与"任务自己失败了"。 */
    private Boolean cancelRequested;
    private LocalDateTime createdAt;
    /** 进入 RUNNING 的时间；仍在排队时为 null。 */
    private LocalDateTime startedAt;
    /** 进入任一终态的时间；未结束时为 null。 */
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
