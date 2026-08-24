package com.richard.fyoung.customeradmin.eval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 给当前有效工作集创建不可变命名版本。 */
public record EvalDatasetVersionCreateRequest(
    @NotBlank @Size(max = 128) String versionName
) {
}
