package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customerwork.safety.tenant.TenantAccessConstants;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;

/**
 * 登录态里的租户信息读写（Sa-Token Session 为载体）。
 *
 * <p>{@code tenantId} 是登录用户自身的归属租户（登录时焊死，全程不可改）；
 * {@code viewTenantId} 是控制面用户切换到的目标租户视角。两个租户值都同时保存对应 access epoch，
 * 用户自身另存 auth epoch；生效租户 = viewTenantId 优先，回落 tenantId。</p>
 *
 * <p>租户归属放登录态，epoch 每请求与数据库权威值比较；切换视角也只改当前用户这一份会话数据。</p>
 * @author owlzhangfq@gmail.com
 */
public final class TenantSession {

    /** 登录用户自身归属的租户。 */
    public static final String KEY_TENANT_ID = "tenantId";

    /** 控制面用户切换到的目标租户视角（普通用户不写此键）。 */
    public static final String KEY_VIEW_TENANT_ID = "viewTenantId";

    /** 登录时的用户认证版本。 */
    public static final String KEY_AUTH_EPOCH = "authEpoch";

    /** 登录时用户归属租户的访问版本。 */
    public static final String KEY_TENANT_ACCESS_EPOCH = TenantAccessConstants.ACCESS_EPOCH_KEY;

    /** 切换视角时目标租户的访问版本。 */
    public static final String KEY_VIEW_TENANT_ACCESS_EPOCH = "viewTenantAccessEpoch";

    private TenantSession() {
    }

    /** 登录成功时把归属租户与双 epoch 一次写入会话。 */
    public static void bindTenant(String tenantId, long authEpoch, long tenantAccessEpoch) {
        StpUtil.getSession().set(KEY_TENANT_ID, TenantContext.canonicalizeTenantId(tenantId));
        StpUtil.getSession().set(KEY_AUTH_EPOCH, authEpoch);
        StpUtil.getSession().set(KEY_TENANT_ACCESS_EPOCH, tenantAccessEpoch);
    }

    /** 读取登录用户的归属租户；未登录返回 {@code null}。 */
    public static String currentUserTenant() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return TenantContext.canonicalizeTenantId(
            (String) StpUtil.getSession().get(KEY_TENANT_ID));
    }

    /** 控制面用户切换视角，同时保存目标租户的访问版本。 */
    public static void switchView(String tenantId, long tenantAccessEpoch) {
        StpUtil.getSession().set(KEY_VIEW_TENANT_ID, TenantContext.canonicalizeTenantId(tenantId));
        StpUtil.getSession().set(KEY_VIEW_TENANT_ACCESS_EPOCH, tenantAccessEpoch);
    }

    /** 回到用户自身租户视角。 */
    public static void clearView() {
        StpUtil.getSession().delete(KEY_VIEW_TENANT_ID);
        StpUtil.getSession().delete(KEY_VIEW_TENANT_ACCESS_EPOCH);
    }

    /** 当前生效租户：切换视角优先，回落用户归属租户；未登录返回 {@code null}。 */
    public static String effectiveTenant() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        String view = (String) StpUtil.getSession().get(KEY_VIEW_TENANT_ID);
        if (view != null && !view.isBlank()) {
            return TenantContext.canonicalizeTenantId(view);
        }
        return TenantContext.canonicalizeTenantId(
            (String) StpUtil.getSession().get(KEY_TENANT_ID));
    }

    public static String currentViewTenant() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        String view = (String) StpUtil.getSession().get(KEY_VIEW_TENANT_ID);
        return view == null || view.isBlank() ? null : TenantContext.canonicalizeTenantId(view);
    }

    public static Long currentAuthEpoch() {
        return sessionLong(KEY_AUTH_EPOCH);
    }

    public static Long currentTenantAccessEpoch() {
        return sessionLong(KEY_TENANT_ACCESS_EPOCH);
    }

    public static Long currentViewTenantAccessEpoch() {
        return sessionLong(KEY_VIEW_TENANT_ACCESS_EPOCH);
    }

    private static Long sessionLong(String key) {
        if (!StpUtil.isLogin()) {
            return null;
        }
        Object value = StpUtil.getSession().get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

}
