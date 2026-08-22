package com.richard.fyoung.customeradmin.aiconfig.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 模型目录资产：描述模型本身，不承载端点和凭据。 */
@Data
@TableName("ai_model_asset")
public class AiModelAsset {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String assetCode;
    private String assetName;
    private String vendor;
    private String modelKey;
    private String family;
    private String assetVersion;
    private String modality;
    private Integer contextWindow;
    private Integer maxOutputTokens;
    private Integer supportsStream;
    private Integer supportsTool;
    private Integer supportsJsonSchema;
    private Integer supportsMultimodal;
    private String capabilityHash;
    private String lifecycleStatus;

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
