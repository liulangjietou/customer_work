package com.richard.fyoung.customeradmin.slo.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租户级 SLO 策略。范围键由 scopeType 决定，TENANT 时为空。 */
@Data
@TableName("ai_slo_policy")
public class SloPolicy {

    /** 低于 100 次时，单次异常会显著放大比例，默认不据此产生生产告警。 */
    public static final int DEFAULT_MINIMUM_SAMPLE_COUNT = 100;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String policyName;
    private String scopeType;
    private String scopeKey;
    private BigDecimal availabilityTarget;
    private BigDecimal latencyTarget;
    private Long latencyThresholdMs;
    private Integer shortWindowMinutes;
    private Integer longWindowMinutes;
    private Integer minimumSampleCount;
    private BigDecimal burnRateThreshold;
    private Boolean enabled;
    /** 以下字段只由周期评估租约 Mapper 更新，策略编辑不得覆盖正在执行的租约。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long nextEvaluationAtMs;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String evaluationLeaseOwner;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long evaluationLeaseUntilMs;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer evaluationFailures;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime lastEvaluatedAt;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String lastEvaluationStatus;
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String lastEvaluationError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
