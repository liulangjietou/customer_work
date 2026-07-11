package com.richard.fyoung.customeradmin.workspace.project.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建/编辑项目请求。
 * @author owlzhangfq@gmail.com
 */
public record ProjectSaveRequest(
    @NotBlank(message = "projectName 不能为空") String projectName,
    String description) {
}
