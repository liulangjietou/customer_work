package com.richard.fyoung.customeradmin.ticket.dto;

/**
 * 转派工单请求：{@code toAgent} 目标坐席登录名，可选（不填由 8080 侧决定转回队列/自动分派）。
 * @author owlzhangfq@gmail.com
 */
public record TicketTransferRequest(String toAgent) {
}
