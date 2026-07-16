package com.richard.fyoung.customeradmin.ticket.dto;

import lombok.Data;

import java.util.List;

/**
 * 工单详情：工单主体 + 事件轨迹（对应 8080 的 {@code {"ticket":..,"events":[..]}}）。
 * @author owlzhangfq@gmail.com
 */
@Data
public class TicketDetailVO {
    private TicketVO ticket;
    private List<TicketEventVO> events;
}
