package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 工单事件（状态流转/操作轨迹）视图对象，透传 8080 契约。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketEventVO {
    /** 事件自增主键。 */
    private Long id;
    /** 工单号（字符串 "TK-&lt;uuid&gt;"）。 */
    private String ticketId;
    private String eventType;
    private String fromStatus;
    private String toStatus;
    private String actorType;
    private String actorId;
    private String note;
    private Long createdAtMs;
}
