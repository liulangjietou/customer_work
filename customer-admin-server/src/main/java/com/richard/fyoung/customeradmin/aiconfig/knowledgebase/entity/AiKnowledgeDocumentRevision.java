package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文档不可变修订，记录父修订、内容指纹、来源位置与运行时 ACL。 */
@Data
@TableName("ai_knowledge_document_revision")
public class AiKnowledgeDocumentRevision {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long documentId;
    private Long sourceId;
    private Long parentRevisionId;
    private String operation;
    private String sourceVersion;
    private String title;
    private String sourceUri;
    private String content;
    private String contentHash;
    private String aclMode;
    private String allowedSubjectTypes;
    private String allowedSubjectIds;
    private String allowedChannels;
    private LocalDateTime sourceUpdatedAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
