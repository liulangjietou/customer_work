package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.core.dto.ChatTerminalEnvelope;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;

/** 一轮对话持久化完成后的领域结果。 */
public record ChatTurnCompletion(ChatMessage message, ChatTerminalEnvelope terminal) {
}
