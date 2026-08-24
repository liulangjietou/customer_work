package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 文档稳定身份；正文与 ACL 只存在于不可变 revision。 */
@Data
@TableName("ai_knowledge_document")
public class AiKnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long knowledgeBaseId;
    private Long sourceId;
    private String externalId;
    private Long currentRevisionId;
    private String sourceVersion;
    private String contentHash;
    private Integer deleted;
    private LocalDateTime sourceUpdatedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
