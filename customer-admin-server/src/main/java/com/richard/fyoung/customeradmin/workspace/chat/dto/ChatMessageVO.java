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
 * @param turnId      当前轮用户消息 ID，历史片段缺少用户输入时为空
 * @param phase       有证据的消息阶段，旧数据缺少结束原因时为 UNKNOWN
 * @param finishReason 框架记录的结束原因，缺失时为空
 * @param sessionType 调用入口类型，缺少元数据时为空
 * @author owlzhangfq@gmail.com
 */
public record ChatMessageVO(String id, String role, String text, String timestamp,
                            List<ChatMessageAttachmentVO> attachments, String turnId,
                            ChatMessagePhase phase, String finishReason, String sessionType) {

    /** 兼容历史缓存与既有调用方，不为缺少元数据的回复补造终态。 */
    public ChatMessageVO(String id, String role, String text, String timestamp,
                         List<ChatMessageAttachmentVO> attachments) {
        this(id, role, text, timestamp, attachments, null,
            "user".equals(role) ? ChatMessagePhase.USER_INPUT : ChatMessagePhase.UNKNOWN, null, null);
    }
}
