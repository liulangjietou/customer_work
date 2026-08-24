package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 知识库版本与文档修订的不可变快照成员关系。 */
@Data
@TableName("ai_knowledge_base_version_document")
public class AiKnowledgeBaseVersionDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long knowledgeBaseVersionId;
    private Long documentRevisionId;
    private Long sourceId;
    private String externalId;
}
