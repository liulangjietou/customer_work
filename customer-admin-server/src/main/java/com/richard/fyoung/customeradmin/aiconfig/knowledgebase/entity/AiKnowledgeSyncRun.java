package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次持久同步运行；requestId 保证上游重试幂等。 */
@Data
@TableName("ai_knowledge_sync_run")
public class AiKnowledgeSyncRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long knowledgeBaseId;
    private Long sourceId;
    private String requestId;
    private String requestHash;
    private String syncMode;
    private String checkpointBefore;
    private String checkpointAfter;
    private String status;
    private Integer receivedCount;
    private Integer upsertedCount;
    private Integer deletedCount;
    private Integer unchangedCount;
    private Integer activeDocumentCount;
    private Integer duplicateContentCount;
    private BigDecimal qualityScore;
    private String qualityStatus;
    private Long knowledgeBaseVersionId;
    private String snapshotHash;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
