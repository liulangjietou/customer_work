package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求向账号登记的邮箱发送密码重置验证码。
 *
 * <p>用户名与邮箱都要填：两者必须指向同一个账号才会真的发信，
 * 但对不上时的响应与"邮箱压根没注册"完全一致（见 {@code PasswordResetService}）。</p>
 *
 * <p>图形验证码在这里<b>无条件</b>校验，不看部署形态——注册那条链路在内网实例可以省掉它，
 * 因为注册要走审核、建出来的号也拿不到任何权限；而找回密码对着的是一个已经存在、
 * 多半已获授权的账号，任何人都能对它发起。</p>
 * @author owlzhangfq@gmail.com
 */
public record PasswordResetEmailCodeRequest(
    @NotBlank(message = "username 不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64 位")
    String username,

    @NotBlank(message = "email 不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    String email,

    @NotBlank(message = "captchaId 不能为空")
    @Size(max = 64, message = "captchaId 非法")
    String captchaId,

    @NotBlank(message = "captcha 不能为空")
    @Size(max = 16, message = "验证码非法")
    String captcha) {
}
