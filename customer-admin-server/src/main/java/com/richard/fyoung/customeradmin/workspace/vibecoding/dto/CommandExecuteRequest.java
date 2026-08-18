package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 交互式沙箱命令执行请求。 */
public record CommandExecuteRequest(
        @NotBlank(message = "sessionId不能为空") String sessionId,
        @NotBlank(message = "command不能为空")
        @Size(max = 4000, message = "command长度不能超过4000字符") String command) {
}
