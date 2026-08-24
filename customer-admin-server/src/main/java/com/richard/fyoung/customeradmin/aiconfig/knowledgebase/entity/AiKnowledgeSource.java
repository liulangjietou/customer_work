package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 知识文档源稳定身份及其最新 checkpoint、质量和新鲜度事实。 */
@Data
@TableName("ai_knowledge_source")
public class AiKnowledgeSource {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long knowledgeBaseId;
    private String sourceCode;
    private String sourceName;
    private String sourceType;
    private Integer status;
    private Integer freshnessSlaMinutes;
    private BigDecimal qualityThreshold;
    private String defaultAclJson;
    private String currentCheckpoint;
    private LocalDateTime lastSyncAt;
    private LocalDateTime lastSuccessfulSyncAt;
    private String lastSyncStatus;
    private String lastSyncError;
    private Integer activeDocumentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private Integer revision;

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
