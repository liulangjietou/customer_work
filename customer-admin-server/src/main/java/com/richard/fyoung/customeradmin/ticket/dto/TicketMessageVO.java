package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 工单会话消息视图对象，透传 8080 契约。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketMessageVO {
    /** 消息自增主键（也是 beforeId 游标）。 */
    private Long id;
    private String messageId;
    private String sessionId;
    /** 工单号（字符串 "TK-&lt;uuid&gt;"）。 */
    private String ticketId;
    /** 发送方类型：用户 / 坐席 / 系统等。 */
    private String senderType;
    private String senderId;
    private String content;
    private Long createdAtMs;
}
