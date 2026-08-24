package com.richard.fyoung.customeradmin.aiconfig.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体-知识库 关联（纯关系表，无审计列，仿 {@code ai_agent_mcp}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_knowledge_base")
public class AiAgentKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long knowledgeBaseId;
    /** 绑定时冻结的不可变知识库版本。 */
    private Long knowledgeBaseVersionId;
}
