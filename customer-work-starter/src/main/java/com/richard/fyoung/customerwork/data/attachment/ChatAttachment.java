package com.richard.fyoung.customerwork.data.attachment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话附件领域对象：一次附件上传的完整可追溯记录（落盘 + 落库）。
 *
 * <p>由 {@link AttachmentParseService} 编排产出：解析成功 {@code parseStatus=SUCCESS} 且 {@code parsedText}
 * 有值；解析失败 {@code parseStatus=FAILED} 且 {@code errorMessage} 记录原因（文件仍已落盘、记录仍落库）。
 * {@code storagePath} 为落盘相对路径（原始文件名只进本对象 / 库，不作为磁盘文件名，防路径穿越）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {

    /** 附件 ID（UUID）。 */
    private String id;
    /** 会话 ID。 */
    private String sessionId;
    /** 绑定的用户消息 ID（框架 {@code Msg.id}，空=未绑定；上传时未知，随对话发送时由 admin 私有链路回填）。 */
    private String messageId;
    /** 上传者标识。 */
    private String uploader;
    /** 来源渠道：user_chat / admin_chat / vibecoding。 */
    private String channel;
    /** 原始文件名。 */
    private String fileName;
    /** 小写扩展名（不含点）。 */
    private String extension;
    /** MIME 类型。 */
    private String mimeType;
    /** 文件字节数。 */
    private long fileSize;
    /** 落盘相对路径。 */
    private String storagePath;
    /** 解析状态。 */
    private AttachmentParseStatus parseStatus;
    /** 解析出的文本（FAILED 时为空）。 */
    private String parsedText;
    /** 解析失败原因（SUCCESS 时为空）。 */
    private String errorMessage;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
