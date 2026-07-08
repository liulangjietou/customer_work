package com.richard.fyoung.customeradmin.system.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 用户新建/编辑请求。新建时 {@code password} 必填（初始密码）；编辑时可不传（不改密码，
 * 改密走 {@code /api/auth/change-password} 本人操作，管理员不能越权改他人密码）。
 * @author owlzhangfq@gmail.com
 */
public record UserSaveRequest(
    @NotBlank(message = "username 不能为空") String username,
    @Size(min = 6, max = 32, message = "密码长度需在 6~32 位之间") String password,
    String nickname,
    Integer status,
    List<Long> roleIds) {
}
