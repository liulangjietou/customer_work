package com.richard.fyoung.customeradmin.workspace.chat.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 工作区对话请求。{@code sessionId} 由前端生成并透传，标识同一次多轮对话
 * （与 agentCode 一起构成 RuntimeContext 的 (userId, sessionId)，见 AdminAgentInstanceFactory#contextFor）。
 *
 * <p>{@code collaboration}（P3-1 多 Agent 协作编程）：VibeCoding 面板"协作模式"开关，默认关（null/false）。
 * 开启后一次需求输入走多角色顺序流水（需求分析→方案设计→编码实现→自测审查），仅 {@code /vibecoding/stream}
 * 端点消费该字段；普通对话链路忽略。可空以保证旧前端向后兼容（Jackson 缺字段解析为 null）。</p>
 * @author owlzhangfq@gmail.com
 */
public record ChatRequest(String sessionId, @NotBlank(message = "message 不能为空") String message,
                          Boolean collaboration) {

    /** 是否启用协作模式（null 视为关闭）。 */
    public boolean collaborationEnabled() {
        return Boolean.TRUE.equals(collaboration);
    }
}
