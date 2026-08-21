package com.richard.fyoung.customeradmin.datascope;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析当前登录用户的生效数据范围：取其全部启用角色中最宽的一档。
 * @author owlzhangfq@gmail.com
 */
@Component
public class DataScopeResolver {

    private final UserRoleResolver userRoleResolver;
    private final CrossTenantAuthority crossTenantAuthority;

    public DataScopeResolver(UserRoleResolver userRoleResolver, CrossTenantAuthority crossTenantAuthority) {
        this.userRoleResolver = userRoleResolver;
        this.crossTenantAuthority = crossTenantAuthority;
    }

    /**
     * 当前用户的生效范围；未登录或不在 Web 上下文时返回 {@code null}（调用方据此不做限制）。
     *
     * <p><b>ALL 额外要求用户具备显式控制面角色</b>。租户管理员可以在自己租户里建角色，
     * 若不加这道校验，任意租户建一个数据范围为 ALL 的角色就能越出自己的租户——
     * 这与 {@code AdminStpInterfaceImpl} 对超管角色额外校验控制面字段是同一个道理：
     * 凡是"能越出本租户"的判定，都不能只看租户自己能改的字段。</p>
     */
    public DataScope resolve() {
        Long userId = currentUserId();
        if (userId == null) {
            return null;
        }
        List<SysRole> roles = userRoleResolver.enabledRolesOf(userId);
        if (roles.isEmpty()) {
            // 一个角色都没有的用户，按最小权限处理
            return DataScope.SELF;
        }
        DataScope widest = null;
        for (SysRole role : roles) {
            widest = DataScope.widest(widest, DataScope.parse(role.getDataScope()));
        }
        if (widest == DataScope.ALL && !crossTenantAuthority.hasAuthority(roles)) {
            return DataScope.TENANT;
        }
        return widest;
    }

    /** 当前登录用户 ID；未登录或脱离 Web 上下文时返回 null（与 MyMetaObjectHandler 同口径）。 */
    private Long currentUserId() {
        try {
            if (!StpUtil.isLogin()) {
                return null;
            }
            return StpUtil.getLoginIdAsLong();
        } catch (SaTokenException e) {
            return null;
        }
    }
}
