package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次不可变的模型上线认证运行。 */
@Data
@TableName("ai_model_certification_run")
public class AiModelCertificationRun {
    /** 探测开始前生成的时间有序 ID；旧慢认证不能凭完成时间覆盖更新运行。 */
    @TableId(type = IdType.INPUT)
    private Long id;
    private String tenantId;
    private Long modelConfigId;
    private String status;
    private Integer endpointRevision;
    private Integer secretVersion;
    private Integer requiredContextTokens;
    private Long maxLatencyMs;
    private BigDecimal maxInputPrice;
    private BigDecimal maxOutputPrice;
    private Long latencyP95Ms;
    private Integer verifiedContextTokens;
    private BigDecimal inputPrice;
    private BigDecimal outputPrice;
    private String currency;
    private String checksJson;
    private String failureCode;
    private String failureMessage;
    private Long triggeredBy;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime validUntil;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
