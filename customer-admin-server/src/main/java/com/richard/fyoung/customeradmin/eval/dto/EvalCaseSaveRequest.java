package com.richard.fyoung.customeradmin.eval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 评测工作集用例写入请求；类型由 URL 决定，来源由服务端记录。 */
public record EvalCaseSaveRequest(
    @NotBlank @Size(max = 64) String caseId,
    @NotBlank @Size(max = 1024) String input,
    @Size(max = 1024) String expected,
    @Size(max = 64) String category,
    Boolean enabled,
    @Size(max = 64) String originRef
) {
}
