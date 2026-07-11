package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 历史会话摘要：会话列表页展示用（不含完整消息内容）。
 *
 * @param sessionId       会话 ID
 * @param preview         首条用户消息预览（截断）
 * @param lastMessageTime 最后一条消息时间（框架侧时间戳字符串，未必是标准格式，仅供前端展示）
 * @param messageCount    消息条数
 * @author owlzhangfq@gmail.com
 */
public record ChatSessionSummary(String sessionId, String preview, String lastMessageTime, int messageCount) {
}
