package com.richard.fyoung.customerwork.core.service;

/**
 * 客服流式输出事件：区分推理思考增量与对外正文增量。
 *
 * <p>思考增量仅用于前端流式展示（如「思考中」折叠面板），不得写入语义缓存，也不计入最终落库答复正文。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public sealed interface ChatStreamEvent permits ChatStreamEvent.Thinking, ChatStreamEvent.Text {

    /** 推理模型的思考过程增量（{@code ThinkingBlockDeltaEvent}）。 */
    record Thinking(String content) implements ChatStreamEvent {
    }

    /** 面向用户的正式答复正文增量（{@code TextBlockDeltaEvent} 或非流式兜底正文）。 */
    record Text(String content) implements ChatStreamEvent {
    }
}
