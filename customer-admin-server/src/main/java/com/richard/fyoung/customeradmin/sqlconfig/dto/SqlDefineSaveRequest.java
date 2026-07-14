package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * SQL 定义新建/编辑请求。保存前 {@code querySql}（及非空的 {@code countSql}）过 SqlValidator 只读校验。
 * @author owlzhangfq@gmail.com
 */
public record SqlDefineSaveRequest(
    @NotBlank(message = "defineKey 不能为空") String defineKey,
    @NotNull(message = "datasourceId 不能为空") Long datasourceId,
    @NotBlank(message = "sqlDescribe 不能为空") String sqlDescribe,
    @NotBlank(message = "querySql 不能为空") String querySql,
    String countSql,
    Boolean autoLoad,
    Boolean enabled,
    String remark) {
}
