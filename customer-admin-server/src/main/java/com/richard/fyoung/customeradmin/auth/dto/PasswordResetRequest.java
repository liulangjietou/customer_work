package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 凭邮箱验证码重置登录密码。
 *
 * <p>密码这里只校验长度上限与非空，<b>强度判定统一走 {@code PasswordPolicy}</b>——
 * 与自助注册同一把尺子，两处各写一份必然漂移。</p>
 * @author owlzhangfq@gmail.com
 */
public record PasswordResetRequest(
    @NotBlank(message = "username 不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64 位")
    String username,

    @NotBlank(message = "email 不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    String email,

    @NotBlank(message = "emailCode 不能为空")
    @Size(max = 16, message = "邮箱验证码非法")
    String emailCode,

    @NotBlank(message = "newPassword 不能为空")
    @Size(min = 8, max = 64, message = "新密码长度需在 8~64 位之间")
    String newPassword,

    @NotBlank(message = "confirmPassword 不能为空")
    @Size(min = 8, max = 64, message = "确认密码长度需在 8~64 位之间")
    String confirmPassword) {
}
