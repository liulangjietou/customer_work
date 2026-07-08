package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 历史会话消息（重新打开一段历史对话时用）。
 *
 * @param role      user / assistant
 * @param text      文本内容
 * @param timestamp 框架侧时间戳字符串
 * @author owlzhangfq@gmail.com
 */
public record ChatMessageVO(String role, String text, String timestamp) {
}
