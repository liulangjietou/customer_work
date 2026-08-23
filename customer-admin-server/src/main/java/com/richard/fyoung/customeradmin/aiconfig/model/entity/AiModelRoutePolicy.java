package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 路由策略稳定身份；规则内容只存在于不可变版本。 */
@Data
@TableName("ai_model_route_policy")
public class AiModelRoutePolicy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String policyCode;
    private String policyName;
    private String description;
    private String status;
    private Long currentVersionId;
    private Integer currentVersionNo;
    private Integer latestVersionNo;
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
