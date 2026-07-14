package com.richard.fyoung.customeradmin.aiconfig.systemtool.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体-系统工具 关联（纯关系表，无审计列，仿 {@code ai_agent_mcp}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_system_tool")
public class AiAgentSystemTool {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long systemToolId;
}
