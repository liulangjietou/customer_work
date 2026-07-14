package com.richard.fyoung.customeradmin.workspace.chat.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式对话的一个增量片段，携带 {@link ChatNodeKind} 供 Controller 侧映射成不同的 SSE
 * {@code event} 名——{@link ChatNodeKind#ANSWER} 走 {@code message} 事件（正文，走 markdown/
 * 代码块渲染），其余类型走 {@code node:<kind小写>} 事件，前端据此把执行轨迹渲染成
 * 开始思考 → 调用大模型/调用 Skill/调用 MCP → 结束思考 这样的时间线，而不是一整段纯文本。
 *
 * <p><b>子 Agent 归属标识</b>：当片段来自 harness spawn 出来的子 Agent（其全量事件经
 * {@code SubagentEventBus} 直推父 sink）时，{@code source}/{@code subagentName} 非空，前端据此把
 * 子 Agent 的执行轨迹渲染进独立的可折叠卡片/分组，而不是和父 Agent 主流程混在一起；来自父 Agent
 * 自身的片段两者均为 {@code null}（{@code io.agentscope.core.agent.EventSource == null}）。</p>
 *
 * @param kind         节点类型
 * @param text         该节点携带的文本（增量思考内容/工具名提示/工具返回结果/正文增量/子 Agent 名称）
 * @param source       子 Agent 的调用链路径（{@code EventSource.getPath()}，如 {@code "main/doc-writer"}），
 *                     {@code null} 表示来自父 Agent 自身
 * @param subagentName 子 Agent 展示名（{@code EventSource.getAgentName()}，为空回退 {@code getAgentId()}），
 *                     {@code null} 表示来自父 Agent 自身
 * @author owlzhangfq@gmail.com
 */
public record ChatStreamChunk(ChatNodeKind kind, String text, String source, String subagentName) {

    /**
     * 父 Agent 片段的便捷构造：{@code source}/{@code subagentName} 均为 {@code null}。
     * 保留旧两参签名，避免改动所有既有调用点（父 Agent 主流程、VibeCoding file_change 等）。
     */
    public ChatStreamChunk(ChatNodeKind kind, String text) {
        this(kind, text, null, null);
    }

    private static final Logger log = LoggerFactory.getLogger(ChatStreamChunk.class);

    /** 仅用于 SSE data 编码的窄用途 mapper（与 ChatHistoryCache 同风格，不依赖容器注入）。 */
    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();

    /**
     * SSE {@code data} 字段编码（与前端 {@code parseChatStreamPayload} 的解析契约配对）：
     * 父 Agent 片段（{@code source == null}）保持发纯文本，完全向后兼容；子 Agent 片段包成
     * {@code {kind, text, source, subagentName}} JSON，前端"先按 JSON 解析、无 text 字段回退纯文本"。
     * 序列化异常时回退纯文本（丢失来源标识但不中断流）。
     */
    public String sseData() {
        if (source == null) {
            return text;
        }
        try {
            return SSE_MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            log.error("[chat] sse chunk json encode failed, code={}, kind={}, source={}",
                "CHAT-SSE-ENCODE-FAIL", kind, source, e);
            return text;
        }
    }
}
