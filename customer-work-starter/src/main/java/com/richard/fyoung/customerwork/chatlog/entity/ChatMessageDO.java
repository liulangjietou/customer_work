package com.richard.fyoung.customerwork.chatlog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 聊天消息表 {@code cw_chat_message} 的持久化对象（贫血 DO）。
 *
 * <p>主键 {@code id} 为自增列（{@link IdType#AUTO}，游标翻页依据），追加后由 MyBatis 回填。发送方类型
 * 在库中以枚举名字符串存储（USER/BOT/AGENT/SYSTEM），DO 侧用 {@link String} 承载。</p>
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_chat_message")
public class ChatMessageDO {

    /** 自增主键（游标翻页）。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    private String messageId;
    private String sessionId;
    private String ticketId;
    private String senderType;
    private String senderId;
    private String content;
    private Long createdAtMs;
}
