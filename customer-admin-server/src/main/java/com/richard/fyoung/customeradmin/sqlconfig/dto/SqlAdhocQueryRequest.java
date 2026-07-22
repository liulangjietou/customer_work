package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 即席（adhoc）SQL 查询请求：指定已配置数据源 + 任意只读 SQL（SQL 客户端用）。
 * 只读性由后端 {@code SqlValidator} + 连接级 readOnly 双重保证，前端只负责传参。
 * @author owlzhangfq@gmail.com
 */
public record SqlAdhocQueryRequest(
    @NotNull(message = "datasourceId 不能为空") Long datasourceId,
    @NotBlank(message = "sql 不能为空") String sql) {
}
