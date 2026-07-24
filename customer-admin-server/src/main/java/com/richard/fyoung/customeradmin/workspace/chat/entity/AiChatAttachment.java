package com.richard.fyoung.customeradmin.workspace.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话附件表 {@code ai_chat_attachment} 的持久化对象（贫血 DO，仅承载列映射）。
 *
 * <p>字段与 starter 的 {@code cw_chat_attachment} 对齐，另加 {@code agentCode}——admin 侧一次附件上传天然
 * 归属某个智能体（Controller 路径参数 {@code {agentCode}}），便于按智能体维度追溯。主键 {@code id} 为应用
 * 赋值的 UUID（{@link IdType#INPUT}）。解析状态在库中以枚举名字符串（SUCCESS/FAILED）存储，与领域对象
 * {@code AttachmentParseStatus} 的互转由 {@code AdminChatAttachmentStore} 负责。驼峰字段自动映射下划线列。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("ai_chat_attachment")
public class AiChatAttachment {

    /** 附件 ID（应用赋值 UUID 字符串主键）。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 会话 ID。 */
    private String sessionId;
    /** 绑定的用户消息 ID（框架 {@code Msg.id}，空=未绑定；随消息发送时由 admin 私有链路回填）。 */
    private String messageId;
    /** 智能体编码（一次上传归属的智能体，starter 领域对象不承载，admin 侧补写）。 */
    private String agentCode;
    /** 上传者标识（当前登录管理员 ID）。 */
    private String uploader;
    /** 来源渠道：admin_chat / vibecoding。 */
    private String channel;
    /** 原始文件名。 */
    private String fileName;
    /** 小写扩展名（不含点）。 */
    private String extension;
    /** MIME 类型。 */
    private String mimeType;
    /** 文件字节数。 */
    private Long fileSize;
    /** 落盘相对路径。 */
    private String storagePath;
    /** 解析状态：SUCCESS / FAILED。 */
    private String parseStatus;
    /** 解析出的文本（FAILED 为空）。 */
    private String parsedText;
    /** 解析失败原因（SUCCESS 为空）。 */
    private String errorMessage;
    /** 创建时间。 */
    private LocalDateTime createdAt;
}
