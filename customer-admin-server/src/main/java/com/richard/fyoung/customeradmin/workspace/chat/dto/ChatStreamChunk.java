package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 流式对话的一个增量片段，携带 {@link ChatNodeKind} 供 Controller 侧映射成不同的 SSE
 * {@code event} 名——{@link ChatNodeKind#ANSWER} 走 {@code message} 事件（正文，走 markdown/
 * 代码块渲染），其余类型走 {@code node:<kind小写>} 事件，前端据此把执行轨迹渲染成
 * 开始思考 → 调用大模型/调用 Skill/调用 MCP → 结束思考 这样的时间线，而不是一整段纯文本。
 *
 * @param kind 节点类型
 * @param text 该节点携带的文本（增量思考内容/工具名提示/工具返回结果/正文增量）
 * @author owlzhangfq@gmail.com
 */
public record ChatStreamChunk(ChatNodeKind kind, String text) {
}
