package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 后台本地账号自助注册请求。
 *
 * <p>密码这里只校验长度上限与非空，<b>强度判定统一在 {@code RegistrationGuard} 一处做</b>——
 * 注解式校验表达不了"对外实例要求 8 位且字母数字混合、内网实例可放宽"这种依赖部署形态的规则，
 * 两边各写一份必然漂移。</p>
 *
 * <p><b>邮箱与邮箱验证码是必填的</b>，但这里不加 {@code @NotBlank}——判定统一在 Guard 一处，
 * 两边各写一份就有两个真相来源。注册请求不带图形码：它只在 {@code POST /api/auth/email-code}
 * 那一步用掉，手里这份邮箱验证码是更强的证据。</p>
 * @author owlzhangfq@gmail.com
 */
public record RegisterRequest(
    @NotBlank(message = "username 不能为空")
    @Size(min = 3, max = 32, message = "用户名长度需在 3~32 位之间")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "用户名只能包含字母、数字、点、下划线和短横线")
    String username,

    @NotBlank(message = "password 不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 位之间")
    String password,

    @NotBlank(message = "confirmPassword 不能为空")
    @Size(min = 6, max = 64, message = "确认密码长度需在 6~64 位之间")
    String confirmPassword,

    @Size(max = 64, message = "昵称长度不能超过 64 位")
    String nickname,

    /** 注册邮箱，必填。 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    String email,

    /** 邮箱验证码，取自 {@code POST /api/auth/email-code} 发到邮箱的那封信，必填。 */
    @Size(max = 16, message = "邮箱验证码非法")
    String emailCode) {
}
