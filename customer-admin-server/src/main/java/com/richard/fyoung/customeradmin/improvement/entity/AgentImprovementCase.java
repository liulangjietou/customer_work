package com.richard.fyoung.customeradmin.improvement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
    private String reevaluationVerdict;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String reevaluationError;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String publishTaskId;
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
}
