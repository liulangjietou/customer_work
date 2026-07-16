package com.richard.fyoung.customeradmin.ticket.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改订单收货地址请求。
 * @author owlzhangfq@gmail.com
 */
public record ModifyAddressRequest(@NotBlank(message = "newAddress 不能为空") String newAddress) {
}
