package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SQL 参数元数据新建/编辑请求。{@code paramType} 须为 ParamType 枚举之一，
 * {@code dropDown} 非空时须为合法 JSON 对象（Service 校验）。
 * @author owlzhangfq@gmail.com
 */
public record SqlDefineParamSaveRequest(
    @NotBlank(message = "paramName 不能为空") String paramName,
    String paramDesc,
    @NotBlank(message = "paramType 不能为空") String paramType,
    Boolean required,
    String defaultValue,
    String dropDown,
    Boolean isPageNum,
    Boolean isPageSize,
    Integer sort) {
}
