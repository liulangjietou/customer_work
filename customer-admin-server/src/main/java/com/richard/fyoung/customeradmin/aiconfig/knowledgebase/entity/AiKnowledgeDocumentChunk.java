package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 不可变文档修订的检索分块及向量。 */
@Data
@TableName("ai_knowledge_document_chunk")
public class AiKnowledgeDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long documentRevisionId;
    private Integer chunkIndex;
    private String content;
    private String embedding;
    private Integer dimensions;
    private LocalDateTime createTime;
}
