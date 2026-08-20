package com.richard.fyoung.customeradmin.common.constant;

/**
 * 内置角色编码（{@code sys_role.role_code}）。
 *
 * <p>超管编码同时决定"鉴权时是否放行全部权限"与"建角色时是否允许选跨租户数据范围"，
 * 两处判定必须用同一个字符串——写歪一处不是报错，而是静默失去或静默获得全部权限。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class SystemRoles {

    /** 超级管理员：权限判定直接放行，且允许 {@code data_scope=ALL}（仍需校验归属平台租户）。 */
    public static final String SUPER_ADMIN = "super_admin";

    private SystemRoles() {
    }
}
