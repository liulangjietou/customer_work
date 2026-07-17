package com.richard.fyoung.customerwork.attachment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话附件表 {@code cw_chat_attachment} 的持久化对象（贫血 DO，仅承载列映射）。
 *
 * <p>主键 {@code id} 为应用赋值的 UUID（{@link IdType#INPUT}）。解析状态在库中以枚举名字符串存储
 * （SUCCESS/FAILED），DO 侧用 {@link String} 承载，领域对象的枚举与之互转由 Store 负责。
 * 驼峰字段自动映射下划线列（{@code mapUnderscoreToCamelCase} 已开）。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_chat_attachment")
public class ChatAttachmentDO {

    /** 附件 ID（应用赋值 UUID 字符串主键）。 */
    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String uploader;
    private String channel;
    private String fileName;
    private String extension;
    private String mimeType;
    private Long fileSize;
    private String storagePath;
    private String parseStatus;
    private String parsedText;
    private String errorMessage;
    private LocalDateTime createdAt;
}
