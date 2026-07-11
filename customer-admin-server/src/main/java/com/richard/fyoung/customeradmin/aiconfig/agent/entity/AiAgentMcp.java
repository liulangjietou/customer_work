package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体-MCP 关联（纯关系表，无审计列）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_mcp")
public class AiAgentMcp {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long mcpId;
}
