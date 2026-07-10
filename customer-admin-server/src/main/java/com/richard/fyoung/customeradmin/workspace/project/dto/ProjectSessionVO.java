package com.richard.fyoung.customeradmin.workspace.project.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目详情里的一条会话链接：预览文本 + 所属智能体 + 时间，点击跳回对应智能体工作区继续聊。
 *
 * <p>{@code stale=true} 表示这条关联指向的会话在 AgentStateStore 里已经查不到内容了（比如底层状态被
 * 清理），前端应该只允许"移出项目"，不允许点击跳转。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
public class ProjectSessionVO {
    private String agentCode;
    private String agentName;
    private String sessionId;
    private String preview;
    private String lastMessageTime;
    private Integer messageCount;
    private LocalDateTime addedTime;
    private boolean stale;
}
