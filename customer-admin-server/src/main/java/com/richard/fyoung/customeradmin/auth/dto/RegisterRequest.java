package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 后台本地账号自助注册请求。
 * @author owlzhangfq@gmail.com
 */
public record RegisterRequest(
    @NotBlank(message = "username 不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3~32 位之间")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "用户名只能包含字母、数字、点、下划线和短横线")
    String username,

    @NotBlank(message = "password 不能为空")
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间")
    String password,

    @NotBlank(message = "confirmPassword 不能为空")
    @Size(min = 6, max = 32, message = "确认密码长度需在 6~32 位之间")
    String confirmPassword,

    @Size(max = 64, message = "昵称长度不能超过 64 位")
    String nickname) {
}
