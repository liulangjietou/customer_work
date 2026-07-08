package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 流式对话的一个增量片段，携带事件类型供 Controller 侧映射成不同的 SSE {@code event} 名——
 * 前端据此把"思考过程"（{@code reasoning}）与"正文"（{@code message}）分开渲染
 * （思考过程可折叠，正文走 markdown/代码块渲染）。
 *
 * @param reasoning 是否为思考过程片段（对应 {@link io.agentscope.core.agent.EventType#REASONING}）；
 *                  否则视为正文（{@link io.agentscope.core.agent.EventType#AGENT_RESULT}）
 * @param text      增量文本
 * @author owlzhangfq@gmail.com
 */
public record ChatStreamChunk(boolean reasoning, String text) {
}
