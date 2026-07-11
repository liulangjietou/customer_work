package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 * @author owlzhangfq@gmail.com
 */
public record LoginRequest(
    @NotBlank(message = "username 不能为空") String username,
    @NotBlank(message = "password 不能为空") String password,
    /** 记住我：默认/缺失时视为 false，为 true 时登录态有效期延长到 admin.remember-me-timeout-seconds 配置值。 */
    Boolean rememberMe) {
}
