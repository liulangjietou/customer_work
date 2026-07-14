package com.richard.fyoung.customeradmin.sqlconfig.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 数据源新建/编辑请求。新建时 {@code password} 必填（Service 校验）；编辑时留空=不改密码，
 * 避免每次编辑都要求重新输入明文（同 {@code ModelSaveRequest.apiKey} 手法）。
 * @author owlzhangfq@gmail.com
 */
public record SqlDatasourceSaveRequest(
    @NotBlank(message = "name 不能为空") String name,
    @NotBlank(message = "jdbcUrl 不能为空") String jdbcUrl,
    @NotBlank(message = "username 不能为空") String username,
    String password,
    Boolean enabled,
    String remark) {
}
