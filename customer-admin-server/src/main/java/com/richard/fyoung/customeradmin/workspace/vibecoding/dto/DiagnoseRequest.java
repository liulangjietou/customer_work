package com.richard.fyoung.customeradmin.workspace.vibecoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bug/日志诊断请求。日志作为不可信数据交给专用提示词处理。 */
public record DiagnoseRequest(
        @NotBlank(message = "sessionId不能为空") String sessionId,
        @NotBlank(message = "log不能为空")
        @Size(max = 100000, message = "log长度不能超过100000字符") String log) {
}
