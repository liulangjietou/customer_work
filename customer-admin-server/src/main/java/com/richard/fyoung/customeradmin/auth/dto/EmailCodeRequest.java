package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 请求向注册邮箱发送验证码。
 *
 * <p>图形验证码在这一步校验：发信是唯一会向站外第三方产生副作用的匿名操作，
 * 它才是最该先挡住脚本的地方。</p>
 * @author owlzhangfq@gmail.com
 */
public record EmailCodeRequest(
    @NotBlank(message = "email 不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    String email,

    @Size(max = 64, message = "captchaId 非法")
    String captchaId,

    @Size(max = 16, message = "验证码非法")
    String captcha) {
}
