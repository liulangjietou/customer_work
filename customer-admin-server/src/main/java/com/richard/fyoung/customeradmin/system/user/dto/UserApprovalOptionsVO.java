package com.richard.fyoung.customeradmin.system.user.dto;

import java.util.List;

/**
 * 注册审核可选项：后端按调用者的跨租户能力收敛租户范围，并只返回目标租户内可用角色。
 * @author owlzhangfq@gmail.com
 */
public record UserApprovalOptionsVO(
    String selectedTenantId,
    List<TenantOption> tenants,
    List<RoleOption> roles) {

    /** 审核可绑定的租户。 */
    public record TenantOption(String tenantId, String tenantName) {
    }

    /** 目标租户内可分配的启用角色。 */
    public record RoleOption(
        Long id,
        String roleName,
        String roleCode,
        boolean controlPlane) {
    }
}
