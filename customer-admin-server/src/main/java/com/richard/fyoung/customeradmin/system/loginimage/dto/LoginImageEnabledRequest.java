package com.richard.fyoung.customeradmin.system.loginimage.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 登录页轮播图启用/禁用请求。
 * @author owlzhangfq@gmail.com
 */
public record LoginImageEnabledRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
}
