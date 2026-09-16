package com.richard.fyoung.customeradmin.aiconfig.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户自己的未完成配置；不参与运行时装配、菜单聚合或渠道发布。 */
@Data
@TableName("ai_agent_draft")
public class AiAgentDraft {
    @TableId(type = IdType.INPUT)
    private String id;
    private String tenantId;
    private Long ownerUserId;
    private Long agentId;
    private Long baseRevision;
    private String title;
    private String configuration;
    private Long version;
    private Long updatedAtMs;
}
