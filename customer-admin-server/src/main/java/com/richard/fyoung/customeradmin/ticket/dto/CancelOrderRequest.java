package com.richard.fyoung.customeradmin.ticket.dto;

/**
 * 取消订单请求（reason 可选，仅登记用途）。
 * @author owlzhangfq@gmail.com
 */
public record CancelOrderRequest(String reason) {
}
