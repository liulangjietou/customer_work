package com.richard.fyoung.customeradmin.tenant;

import java.util.Set;

/**
 * 控制面专属权限策略。
 *
 * <p>控制面操作同时要求显式控制面角色与权限点。这里定义的是“普通租户角色不应被授予”的
 * 权限集合，供租户开通流程统一过滤；Controller 仍必须独立校验 {@link CrossTenantAuthority}，
 * 不能把角色授权配置当成安全边界。</p>
 * @author owlzhangfq@gmail.com
 */
public final class ControlPlanePermissions {

    private static final Set<String> CONTROL_PLANE_FAMILIES = Set.of(
        "tenant",
        "menu",
        "login-image",
        "system-tool",
        "config-version"
    );

    private static final Set<String> CONTROL_PLANE_CODES = Set.of(
        "billing:quota-edit",
        "billing:price-edit",
        "billing:export",
        "billing:aggregate",
        "sensitive-word:add",
        "sensitive-word:edit",
        "sensitive-word:delete"
    );

    private ControlPlanePermissions() {
    }

    /** 判断权限点是否仅允许授予控制面角色。 */
    public static boolean isControlPlaneOnly(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        if (CONTROL_PLANE_CODES.contains(permissionCode)) {
            return true;
        }
        return CONTROL_PLANE_FAMILIES.stream()
            .anyMatch(family -> permissionCode.equals(family) || permissionCode.startsWith(family + ":"));
    }
}
