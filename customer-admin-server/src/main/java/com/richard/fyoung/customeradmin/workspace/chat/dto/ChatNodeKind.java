package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 对话/VibeCoding 流式过程中的节点类型，前端按这个字段把执行轨迹渲染成时间线（而不是一整段
 * 纯文本思考过程）。{@link #ANSWER} 走独立的 {@code message} SSE 事件（增量正文，不进时间线），
 * 其余类型都走 {@code node:<kind小写>} SSE 事件。
 * @author owlzhangfq@gmail.com
 */
public enum ChatNodeKind {
    /** 可见回答正文增量。 */
    ANSWER,
    /** 开始思考：本轮对话时间线的首节点，只出现一次。 */
    THINKING_START,
    /** 思考内容增量（{@code ThinkingBlock}）。 */
    THINKING,
    /** 结束思考：本轮对话时间线的末节点，只出现一次（正常结束或异常兜底都会补发）。 */
    THINKING_END,
    /** 调用大模型：ReAct 循环每进入新一轮推理都会各记一条，真实反映模型被调用的次数。 */
    MODEL_CALL,
    /** 调用 Skill 工具。 */
    TOOL_SKILL,
    /** 调用 MCP 工具。 */
    TOOL_MCP,
    /** 调用内置/业务工具（既非 Skill 也非 MCP）。 */
    TOOL_BUILTIN,
    /** 工具返回结果。 */
    TOOL_RESULT,
    /** 子 Agent 开始：某个 harness spawn 出的子 Agent 首次产出事件时补发一次，text 为子 Agent 展示名。 */
    SUBAGENT_START,
    /** 子 Agent 完成：子 Agent 的 {@code AGENT_RESULT}（最终文本），不走父 Agent 的 {@link #ANSWER} 正文链路。 */
    SUBAGENT_RESULT,
    /** VibeCoding 专用：会话 workspace 文件发生变更（新增/修改/删除），仅由 VibeCodingService 产出。 */
    FILE_CHANGE,

    /** VibeCoding 专用：沙箱内编译/测试命令的结构化执行报告，仅由 VibeCodingService 从 {@code execute} 工具结果解析产出。 */
    TEST_REPORT,

    /** VibeCoding 专用（Plan Mode HITL）：高风险操作待人工确认的计划事件，由 PlanConfirmationService 产出。 */
    PLAN,

    /** VibeCoding 专用（Plan Mode HITL）：某个计划被确认/拒绝/超时后的终态通知。 */
    PLAN_RESULT;

    /**
     * 映射成 SSE {@code event} 名：{@link #ANSWER} 走 {@code message}（向后兼容旧协议，前端正文
     * 渲染逻辑不用改）；{@link #FILE_CHANGE} 走独立的 {@code file_change} 事件（前端按固定 JSON
     * 结构解析，不走时间线节点渲染）；其余节点类型统一走 {@code node:<kind小写>}，前端按事件名
     * 前缀分流渲染成执行轨迹时间线，不用解析 JSON。
     */
    public String sseEventName() {
        if (this == ANSWER) {
            return "message";
        }
        if (this == FILE_CHANGE) {
            return "file_change";
        }
        if (this == TEST_REPORT) {
            return "test_report";
        }
        if (this == PLAN) {
            return "plan";
        }
        if (this == PLAN_RESULT) {
            return "plan_result";
        }
        return "node:" + name().toLowerCase();
    }
}
