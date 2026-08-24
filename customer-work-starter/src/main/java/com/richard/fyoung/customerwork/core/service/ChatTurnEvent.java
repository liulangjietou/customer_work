package com.richard.fyoung.customerwork.core.service;

/** 应用层消费的统一对话流：正文增量若干，最后且仅最后一个持久化完成事件。 */
public sealed interface ChatTurnEvent permits ChatTurnEvent.Delta, ChatTurnEvent.Completed {

    record Delta(String content) implements ChatTurnEvent {
    }

    record Completed(ChatTurnCompletion completion) implements ChatTurnEvent {
    }
}
