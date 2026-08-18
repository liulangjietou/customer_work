package com.richard.fyoung.customeradmin.system.role.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 角色新建/编辑请求，{@code permissionIds} 为该角色勾选的权限点全量集合（覆盖式替换）。
 *
 * <p>{@code dataScope} 为空时按 {@code SELF} 落库（最小权限），取值见
 * {@link com.richard.fyoung.customeradmin.datascope.DataScope}。</p>
 * @author owlzhangfq@gmail.com
 */
public record RoleSaveRequest(
    @NotBlank(message = "roleName 不能为空") String roleName,
    @NotBlank(message = "roleCode 不能为空") String roleCode,
    String remark,
    Integer status,
    String dataScope,
    List<Long> permissionIds) {
}
