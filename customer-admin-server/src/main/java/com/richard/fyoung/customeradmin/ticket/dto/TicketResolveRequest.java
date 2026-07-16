package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 解决工单请求：{@code note} 解决说明。
 * @author owlzhangfq@gmail.com
 */
public record TicketResolveRequest(@NotBlank(message = "note 不能为空") String note) {
}
