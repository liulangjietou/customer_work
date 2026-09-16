package com.richard.fyoung.customeradmin.improvement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementCaseStatus;
import com.richard.fyoung.customeradmin.improvement.domain.ImprovementReevaluationStatus;
import java.util.Objects;
import lombok.Data;

/**
 * KnowledgeGap/badcase 的治理工作流事实。
 *
 * <p>原始信号留在客服库，本表只保存责任、证据和状态。发布任务也在 Admin 库，因而发布入队与
 * 工作流推进可由同一事务保证，不做不可恢复的跨库双写。</p>
 */
@Data
@TableName("ai_agent_improvement_case")
public class AgentImprovementCase {
    public static final String REEVALUATION_TIMEOUT_MESSAGE = "复评已超过执行时限，可能因服务中断或模型响应超时；请核对候选后重新发起";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String sourceType;
    private String sourceKey;
    private String signalHash;
    private Long sourceSignalCount;
    private String ownerId;
    private Long slaDueAtMs;
    private String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long agentId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String agentCode;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String artifactType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String artifactVersion;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String candidateVersionsJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String evalType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String evalCaseId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String evalRunId;
    private String reevaluationStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reevaluationAttemptId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long reevaluationDeadlineAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reevaluationVerdict;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reevaluationError;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String publishTaskId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long publishRequestedBy;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String publishRevision;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String publishStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long publishedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long baselineSignalCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long observationStartedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long observationEndsAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer minExposureCalls;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer maxRecurrenceSignals;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long observedCalls;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long observedSignals;
    private String effectStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long lastObservedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long nextActionAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaseOwner;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long leaseUntilMs;
    private Integer automationFailures;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    private Long createdAtMs;
    private Long updatedAtMs;

    /** 每次发起生成独立执行事实；状态允许性由持有行锁的编排入口核对。 */
    public void beginReevaluation(String attemptId, long deadlineAtMs, long now) {
        reevaluationAttemptId = attemptId;
        reevaluationDeadlineAtMs = deadlineAtMs;
        status = ImprovementCaseStatus.REEVALUATING.name();
        reevaluationStatus = ImprovementReevaluationStatus.RUNNING.name();
        reevaluationError = null;
        nextActionAtMs = deadlineAtMs;
        leaseOwner = null;
        leaseUntilMs = 0L;
        automationFailures = 0;
        lastError = null;
        updatedAtMs = now;
    }

    /** 同一候选重新执行后，前一次请求的成功与失败都不再拥有状态更新权。 */
    public boolean isRunningReevaluation(String attemptId) {
        return ImprovementCaseStatus.REEVALUATING.name().equals(status)
            && attemptId != null && Objects.equals(reevaluationAttemptId, attemptId);
    }

    /** 截止时间使用持久化绝对时刻，进程重启后仍可核对。 */
    public boolean reevaluationExpired(long now) {
        return reevaluationDeadlineAtMs != null && now >= reevaluationDeadlineAtMs;
    }

    /** 仅本次执行可明确失败；外部异常迟到时不覆盖下一次执行。 */
    public boolean failReevaluation(String attemptId, String error, long now) {
        if (!isRunningReevaluation(attemptId)) return false;
        status = ImprovementCaseStatus.REEVALUATION_FAILED.name();
        reevaluationStatus = ImprovementReevaluationStatus.FAILED.name();
        reevaluationError = error;
        nextActionAtMs = Long.MAX_VALUE;
        leaseOwner = null;
        leaseUntilMs = 0L;
        updatedAtMs = now;
        return true;
    }
}
