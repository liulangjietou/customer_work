package com.richard.fyoung.customeradmin.system.user.domain;

/**
 * 后台自助注册审核状态。
 *
 * <p>账号启停由 {@code sys_user.status} 表达，注册准入由本状态表达，两者不能复用：
 * 待审核用户允许登录查看首页，而被禁用用户必须拒绝登录。</p>
 * @author owlzhangfq@gmail.com
 */
public enum UserApprovalStatus {

    /** 自助注册后等待管理员审核。 */
    PENDING,

    /** 管理员已审核通过，可以按已分配角色获取权限。 */
    APPROVED,

    /** 管理员审核拒绝，不授予任何角色权限。 */
    REJECTED;

    /** 只有审核通过的账号可以解析角色与权限。 */
    public boolean allowsPermissions() {
        return this == APPROVED;
    }

    /**
     * 数据库值按最小权限解析：空值或脏值一律视为待审核，避免异常数据意外放权。
     */
    public static UserApprovalStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
