package com.richard.fyoung.customeradmin.system.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 权限/菜单新建/编辑请求。
 * @author owlzhangfq@gmail.com
 */
public record PermissionSaveRequest(
    Long parentId,
    @NotBlank(message = "permName 不能为空") String permName,
    @NotBlank(message = "permCode 不能为空") String permCode,
    @NotNull(message = "type 不能为空") Integer type,
    String path,
    String icon,
    /** library=图标库图标名 / image=上传图片URL；不传时默认 library。 */
    String iconType,
    Integer sort) {
}
