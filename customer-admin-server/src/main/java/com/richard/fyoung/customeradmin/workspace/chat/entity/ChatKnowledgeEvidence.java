package com.richard.fyoung.customeradmin.workspace.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 与权威消息关联的检索快照，只留元数据，不复制原文或检索问题。 */
@Data
@TableName("ai_chat_knowledge_evidence")
public class ChatKnowledgeEvidence {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String stateUserId;
    private String agentCode;
    private String sessionId;
    private String turnId;
    private String messageId;
    private String retrievals;
    private Long createdAtMs;
}
