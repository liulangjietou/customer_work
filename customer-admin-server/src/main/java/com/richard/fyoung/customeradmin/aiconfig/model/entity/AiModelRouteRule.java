package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不可变版本中的一条确定性路由规则。 */
@Data
@TableName("ai_model_route_rule")
public class AiModelRouteRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long policyVersionId;
    private String purpose;
    private Long deploymentId;
    private Integer priority;
    private String conditionJson;
    private String conditionSummary;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
