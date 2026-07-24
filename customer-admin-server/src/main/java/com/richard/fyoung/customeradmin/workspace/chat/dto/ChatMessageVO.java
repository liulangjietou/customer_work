package com.richard.fyoung.customeradmin.workspace.chat.dto;

import java.util.List;

/**
 * 历史会话消息（重新打开一段历史对话时用）。
 *
 * @param id          框架消息 ID（{@code Msg.getId()}）：附件按此 id 分组挂回对应用户消息
 * @param role        user / assistant
 * @param text        文本内容
 * @param timestamp   框架侧时间戳字符串
 * @param attachments 该消息绑定的附件（无附件时为空列表，不为 null）
 * @author owlzhangfq@gmail.com
 */
public record ChatMessageVO(String id, String role, String text, String timestamp,
                            List<ChatMessageAttachmentVO> attachments) {
}
