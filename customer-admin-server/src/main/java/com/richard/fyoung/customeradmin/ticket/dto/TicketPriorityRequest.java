package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 调整工单优先级请求。
 * @author owlzhangfq@gmail.com
 */
public record TicketPriorityRequest(@NotBlank(message = "priority 不能为空") String priority) {
}
