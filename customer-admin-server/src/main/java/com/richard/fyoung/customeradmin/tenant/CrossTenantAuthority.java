package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 跨租户控制面权限的唯一判定入口。
 *
 * <p>租户归属只回答“数据属于谁”，不能表达“谁可以越出租户边界”。因此这里既不比较租户编码，
 * 也不根据可由租户自建的角色编码推断权限，只认当前用户已解析角色上的 {@code control_plane=1}。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class CrossTenantAuthority {

    private final UserRoleResolver userRoleResolver;

    public CrossTenantAuthority(UserRoleResolver userRoleResolver) {
        this.userRoleResolver = userRoleResolver;
    }

    /** 当前登录用户是否具备跨租户控制面权限；未登录或脱离 Web 上下文时一律拒绝。 */
    public boolean hasCurrentUserAuthority() {
        Long userId = currentUserId();
        return userId != null && hasAuthority(userRoleResolver.enabledRolesOf(userId));
    }

    /** 要求当前用户具备控制面能力，供所有全局写入口复用同一个 fail-fast 边界。 */
    public void requireCurrentUserAuthority() {
        if (!hasCurrentUserAuthority()) {
            throw new BizException(ResultCode.TENANT_VIEW_FORBIDDEN);
        }
    }

    /** 对已经解析出的角色集合做判定，供鉴权与数据范围链路复用，避免重复查询。 */
    public boolean hasAuthority(List<SysRole> roles) {
        return !CollectionUtils.isEmpty(roles) && roles.stream().anyMatch(this::isControlPlaneRole);
    }

    /** 单个角色是否显式具备控制面能力。 */
    public boolean isControlPlaneRole(SysRole role) {
        return role != null && Integer.valueOf(SysRole.CONTROL_PLANE_ENABLED).equals(role.getControlPlane());
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }
}
