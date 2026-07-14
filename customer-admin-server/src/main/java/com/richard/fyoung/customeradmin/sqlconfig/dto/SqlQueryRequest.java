package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * 通用查询/导出请求：{@code defineKey} + 参数名到值的映射。
 * @author owlzhangfq@gmail.com
 */
public record SqlQueryRequest(
    @NotBlank(message = "defineKey 不能为空") String defineKey,
    Map<String, Object> params) {
}
