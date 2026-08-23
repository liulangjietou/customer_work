package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 路由策略不可变版本；业务层只允许插入内容，发布时仅迁移状态。 */
@Data
@TableName("ai_model_route_policy_version")
public class AiModelRoutePolicyVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long policyId;
    private Integer versionNo;
    private String status;
    private String contentHash;
    private String changeNote;
    private Long activatedBy;
    private LocalDateTime activatedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
