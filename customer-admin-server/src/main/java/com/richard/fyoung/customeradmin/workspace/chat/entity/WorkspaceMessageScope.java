package com.richard.fyoung.customeradmin.workspace.chat.entity;

/** 一条受理记录的归属：认证请求的租户/用户和已通过会话守卫的资源路径。 */
public record WorkspaceMessageScope(String tenantId, long ownerId, String agentCode, String sessionId,
                                    String channel, String clientMessageId) { }
