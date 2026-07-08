package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改密请求。
 * @author owlzhangfq@gmail.com
 */
public record ChangePasswordRequest(
    @NotBlank(message = "oldPassword 不能为空") String oldPassword,
    @NotBlank(message = "newPassword 不能为空")
    @Size(min = 6, max = 32, message = "新密码长度需在 6~32 位之间") String newPassword) {
}
