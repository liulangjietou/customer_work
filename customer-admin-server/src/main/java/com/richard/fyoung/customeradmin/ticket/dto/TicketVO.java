package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

/**
 * 工单视图对象（透传 8080 坐席 API 的 TicketVO，字段与 8080 契约一一对应）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketVO {
    /** 工单号：8080 侧 starter 生成的字符串 "TK-&lt;uuid&gt;"，不是数值自增主键。 */
    private String id;
    private String sessionId;
    private String userId;
    private String title;
    private String category;
    private String priority;
    private String status;
    /** 当前受理坐席（登录名）。 */
    private String assignee;
    private String handoffReason;
    private String resolveNote;
    private Integer reopenCount;
    private Long createdAtMs;
    private Long updatedAtMs;
    private Long handoffAtMs;
    private Long claimedAtMs;
    private Long resolvedAtMs;
    private Long closedAtMs;
}
