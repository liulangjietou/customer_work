package com.richard.fyoung.customeradmin.workspace.chat.dto;

import com.richard.fyoung.customerwork.attachment.AttachmentParseStatus;
import com.richard.fyoung.customerwork.attachment.ChatAttachment;

/**
 * 附件上传解析结果（前端契约）：{@code {id, fileName, content, parseStatus, errorMessage, mimeType, fileSize}}。
 *
 * <p>与 admin-web {@code ChatAttachmentResult} 严丝合缝：{@code content} 为解析文本（FAILED 时为空串），
 * {@code parseStatus} 为枚举名字符串（{@code SUCCESS}/{@code FAILED}），{@code errorMessage} 解析失败原因
 * （SUCCESS 时为 {@code null}）。前端据 {@code parseStatus} 决定失败附件标红提示、成功附件拼进消息正文。
 * {@code mimeType}/{@code fileSize} 供前端按类型选预览方式（文本内联 / 图片 / 走下载接口）并展示大小。</p>
 * @author owlzhangfq@gmail.com
 */
public record ChatAttachmentDTO(
    String id,
    String fileName,
    String content,
    String parseStatus,
    String errorMessage,
    String mimeType,
    long fileSize) {

    /** 领域对象 → 前端 DTO：{@code parsedText} 映射为 {@code content}（空值归一化为空串），枚举取 name()。 */
    public static ChatAttachmentDTO from(ChatAttachment attachment) {
        AttachmentParseStatus status = attachment.getParseStatus();
        return new ChatAttachmentDTO(
            attachment.getId(),
            attachment.getFileName(),
            attachment.getParsedText() == null ? "" : attachment.getParsedText(),
            status == null ? null : status.name(),
            attachment.getErrorMessage(),
            attachment.getMimeType(),
            attachment.getFileSize());
    }
}
