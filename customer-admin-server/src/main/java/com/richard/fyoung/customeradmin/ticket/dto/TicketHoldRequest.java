package com.richard.fyoung.customeradmin.ticket.dto;

/**
 * 挂起工单请求：{@code reason} 挂起原因，可选。
 * @author owlzhangfq@gmail.com
 */
public record TicketHoldRequest(String reason) {
}
