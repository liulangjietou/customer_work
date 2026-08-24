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
    /** 多租户行级隔离列；恢复扫描时用于重建线程租户上下文。 */
    private String tenantId;
    /** 状态：PENDING / RUNNING / COMPLETED / FAILED / CANCELLED。 */
    private String status;
    /** 成功时的结果文本。 */
    private String result;
    /** 失败时的错误信息。 */
    private String errorMessage;
    /** 是否已请求取消：取消请求与实际终态分开记，便于区分"用户点了取消"与"任务自己失败了"。 */
    private Boolean cancelRequested;
    /** 当前执行所有者（Pod/进程唯一 ID）。 */
    private String ownerId;
    /** 所有权租约到期时间；过期后其它实例可 CAS 接管。 */
    private LocalDateTime leaseUntil;
    /** 当前所有者最近一次心跳。 */
    private LocalDateTime heartbeatAt;
    /** 已领取执行次数，首次提交为 1，每次宕机接管递增。 */
    private Integer attemptCount;
    /** 是否具备完整可重放输入。 */
    private Boolean replayable;
    /** 子智能体原始任务提示词。 */
    private String taskInput;
    /** 恢复执行使用的稳定子会话 ID。 */
    private String childSessionId;
    /** 原 RuntimeContext.userId，用于状态/记忆分区。 */
    private String runtimeUserId;
    /** 可信主体类型。 */
    private String subjectType;
    /** 可信主体 ID（API Key 仅持久化指纹）。 */
    private String subjectId;
    private Boolean subjectAuthenticated;
    private Long accessEpoch;
    private String channelCode;
    private LocalDateTime createdAt;
    /** 进入 RUNNING 的时间；仍在排队时为 null。 */
    private LocalDateTime startedAt;
    /** 进入任一终态的时间；未结束时为 null。 */
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
