package com.richard.fyoung.customeradmin.common.constant;

/**
 * 内置角色编码（{@code sys_role.role_code}）。
 *
 * <p>超管全权限还必须同时满足角色上的 {@code control_plane=1}；角色编码本身不是跨租户授权依据。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class SystemRoles {

    /** 超级管理员角色编码；全权限仍需同一角色显式具备控制面能力。 */
    public static final String SUPER_ADMIN = "super_admin";

    private SystemRoles() {
    }
}
