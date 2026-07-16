package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 调整工单分类请求。
 * @author owlzhangfq@gmail.com
 */
public record TicketCategoryRequest(@NotBlank(message = "category 不能为空") String category) {
}
