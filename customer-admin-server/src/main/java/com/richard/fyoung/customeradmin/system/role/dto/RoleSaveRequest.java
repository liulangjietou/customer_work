package com.richard.fyoung.customeradmin.system.role.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 角色新建/编辑请求，{@code permissionIds} 为该角色勾选的权限点全量集合（覆盖式替换）。
 * @author owlzhangfq@gmail.com
 */
public record RoleSaveRequest(
    @NotBlank(message = "roleName 不能为空") String roleName,
    @NotBlank(message = "roleCode 不能为空") String roleCode,
    String remark,
    Integer status,
    List<Long> permissionIds) {
}
