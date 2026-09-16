package com.richard.fyoung.customerwork.capability.assist;

/** 坐席辅助快照；工单绑定由服务端解析，依据只包含已授权会话的实际消息。 */
public record TicketAssistView(String ticketId, ConversationSummary summary) { }
