package com.richard.fyoung.customeradmin.workspace.chat.dto;

/**
 * 历史消息挂载的附件摘要（前端契约）：重新打开历史会话时，随对应用户消息一并返回其绑定的附件。
 *
 * <p>只带展示 + 二次拉取所需的轻量字段——{@code id} 供前端点开时调详情接口取解析文本 / 调 file 接口下载原文件，
 * {@code mimeType}/{@code fileSize} 供选预览方式与展示大小，{@code parseStatus}（{@code SUCCESS}/{@code FAILED}）
 * 供标红提示解析失败的附件。解析文本 {@code parsedText} 不在历史列表内联（可能上万字），按需走详情接口拉取。</p>
 *
 * @param id          附件 ID
 * @param fileName    原始文件名
 * @param mimeType    MIME 类型
 * @param fileSize    文件字节数
 * @param parseStatus 解析状态：SUCCESS / FAILED（枚举名字符串）
 * @author owlzhangfq@gmail.com
 */
public record ChatMessageAttachmentVO(
    String id,
    String fileName,
    String mimeType,
    long fileSize,
    String parseStatus) {
}
