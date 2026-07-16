package com.richard.fyoung.customeradmin.ticket.dto;

/**
 * 恢复（取消挂起）工单请求：{@code reason} 可选，仅用于 admin 侧操作日志留痕，
 * 8080 的 {@code /resume} 无参数，不向上游转发。
 * @author owlzhangfq@gmail.com
 */
public record TicketResumeRequest(String reason) {
}
