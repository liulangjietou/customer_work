package com.richard.fyoung.customeradmin.eval.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 一次最多导入 1000 条；服务端先全量校验，再用单条批量 SQL 写入。 */
public record EvalDatasetImportRequest(
    @NotEmpty @Size(max = 1000) List<@Valid EvalCaseSaveRequest> cases
) {
}
