package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customerwork.tenant.TenantContext;

/**
 * 登录态里的租户信息读写（Sa-Token Session 为载体）。
 *
 * <p>存两个值：{@code tenantId} 是登录用户自身的归属租户（登录时焊死，全程不可改）；
 * {@code viewTenantId} 是运营方切换到的目标租户视角（仅运营方可设，可随时切换）。
 * 生效租户 = viewTenantId 优先，回落 tenantId。</p>
 *
 * <p>租户归属放登录态而非每请求查库，是因为它在登录后就不会变；运营方切视角也只改自己这一份会话数据。</p>
 * @author owlzhangfq@gmail.com
 */
public final class TenantSession {

    /** 登录用户自身归属的租户。 */
    public static final String KEY_TENANT_ID = "tenantId";

    /** 运营方切换到的目标租户视角（非运营方不写此键）。 */
    public static final String KEY_VIEW_TENANT_ID = "viewTenantId";

    private TenantSession() {
    }

    /** 登录成功时写入归属租户。 */
    public static void bindTenant(String tenantId) {
        StpUtil.getSession().set(KEY_TENANT_ID, tenantId);
    }

    /** 读取登录用户的归属租户；未登录返回 {@code null}。 */
    public static String currentUserTenant() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return (String) StpUtil.getSession().get(KEY_TENANT_ID);
    }

    /** 运营方切换视角；传空表示回到平台自身视角。 */
    public static void switchView(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            StpUtil.getSession().delete(KEY_VIEW_TENANT_ID);
            return;
        }
        StpUtil.getSession().set(KEY_VIEW_TENANT_ID, tenantId);
    }

    /** 当前生效租户：运营方视角优先，回落用户归属租户；未登录返回 {@code null}。 */
    public static String effectiveTenant() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        String view = (String) StpUtil.getSession().get(KEY_VIEW_TENANT_ID);
        if (view != null && !view.isBlank()) {
            return view;
        }
        return (String) StpUtil.getSession().get(KEY_TENANT_ID);
    }

    /** 当前登录用户是否为平台运营方（决定能否切换视角、能否做跨租户查询）。 */
    public static boolean isPlatformOperator() {
        return TenantContext.PLATFORM.equals(currentUserTenant());
    }
}
