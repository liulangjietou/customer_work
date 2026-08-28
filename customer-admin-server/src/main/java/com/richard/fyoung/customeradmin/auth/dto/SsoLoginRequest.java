package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * OA 域账号（LDAP/AD）单点登录请求。
 * @author owlzhangfq@gmail.com
 */
public record SsoLoginRequest(
    @NotBlank(message = "username 不能为空")
    @Size(max = 256, message = "username 长度不能超过 256") String username,
    @NotBlank(message = "password 不能为空")
    @Size(max = 256, message = "password 长度不能超过 256") String password,
    /** 记住我：默认/缺失时视为 false，为 true 时登录态有效期延长到 admin.remember-me-timeout-seconds 配置值。 */
    Boolean rememberMe,
    @NotBlank(message = "captchaProof 不能为空")
    @Size(max = 256, message = "captchaProof 长度不能超过 256") String captchaProof) {
}
