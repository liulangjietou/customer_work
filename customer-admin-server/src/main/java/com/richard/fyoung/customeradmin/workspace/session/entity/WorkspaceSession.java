package com.richard.fyoung.customeradmin.workspace.session.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 工作区会话归属记录。 */
@Data
@TableName("ai_workspace_session")
public class WorkspaceSession {

    @TableId
    private Long id;
    private String tenantId;
    private String agentCode;
    private String sessionId;
    private Long ownerUserId;
    private Long createdAtMs;
    private Long updatedAtMs;
}
