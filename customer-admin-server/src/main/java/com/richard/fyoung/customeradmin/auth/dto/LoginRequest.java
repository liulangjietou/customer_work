package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 * @author owlzhangfq@gmail.com
 */
public record LoginRequest(
    @NotBlank(message = "username 不能为空") String username,
    @NotBlank(message = "password 不能为空") String password) {
}
