package com.richard.fyoung.customerwork.core.service;

/** 应用层消费的统一对话流：思考增量与正文增量若干，最后且仅最后一个持久化完成事件。 */
public sealed interface ChatTurnEvent permits ChatTurnEvent.Thinking, ChatTurnEvent.Delta, ChatTurnEvent.Completed {

    record Thinking(String content) implements ChatTurnEvent {
    }

    record Delta(String content) implements ChatTurnEvent {
    }

    record Completed(ChatTurnCompletion completion) implements ChatTurnEvent {
    }
}
