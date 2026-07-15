package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 智能体-子智能体关联（纯关系表，无审计列）。父智能体勾选 subagent 能力后，
 * 从智能体列表多选其他智能体作为可编排的子智能体。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_agent_sub_agent")
public class AiAgentSubAgent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private Long subAgentId;
}
