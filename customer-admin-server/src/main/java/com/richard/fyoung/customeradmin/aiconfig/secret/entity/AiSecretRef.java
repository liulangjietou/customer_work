package com.richard.fyoung.customeradmin.aiconfig.secret.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 凭据引用元数据；不包含任何密钥值。 */
@Data
@TableName("ai_secret_ref")
public class AiSecretRef {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String refCode;
    private String refName;
    private String providerType;
    private String externalRef;
    private Integer currentVersion;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastRotatedAt;
    private Long lastRotatedBy;

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
