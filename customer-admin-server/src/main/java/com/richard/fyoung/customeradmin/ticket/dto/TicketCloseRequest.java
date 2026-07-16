package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 关闭工单请求：{@code reason} 关闭原因。
 * @author owlzhangfq@gmail.com
 */
public record TicketCloseRequest(@NotBlank(message = "reason 不能为空") String reason) {
}
