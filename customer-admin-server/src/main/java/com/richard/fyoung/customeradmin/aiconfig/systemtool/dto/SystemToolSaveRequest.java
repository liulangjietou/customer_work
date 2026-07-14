package com.richard.fyoung.customeradmin.aiconfig.systemtool.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 系统工具编辑请求。工具目录是代码定义的，{@code toolCode} 与其绑定的 Bean 不可修改，
 * 故编辑请求体只含名称/描述/启停/备注，不含 {@code toolCode}。
 * @author owlzhangfq@gmail.com
 */
public record SystemToolSaveRequest(
    @NotBlank(message = "toolName 不能为空") String toolName,
    String description,
    Integer enabled,
    String remark) {
}
