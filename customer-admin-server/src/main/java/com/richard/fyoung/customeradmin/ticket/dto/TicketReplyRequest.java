package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 坐席回复工单请求。
 * @author owlzhangfq@gmail.com
 */
public record TicketReplyRequest(@NotBlank(message = "content 不能为空") String content) {
}
