package com.richard.fyoung.customeradmin.datascope;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link DataScopeResolver} 单测：多角色取最宽、ALL 需平台归属、无登录态不限制。
 * @author owlzhangfq@gmail.com
 */
class DataScopeResolverTest {

    private static final long USER_ID = 5L;

    private UserRoleResolver userRoleResolver;
    private DataScopeResolver resolver;

    @BeforeEach
    void setUp() {
        userRoleResolver = mock(UserRoleResolver.class);
        resolver = new DataScopeResolver(userRoleResolver);
    }

    @Test
    void resolve_shouldReturnNullWhenNotLogin() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertNull(resolver.resolve());
        }
    }

    /** 一个角色都没有的用户按最小权限处理，而不是"没配置就放行"。 */
    @Test
    void resolve_shouldFallbackToSelfWhenNoRole() {
        assertEquals(DataScope.SELF, resolveWith(List.of(), true));
    }

    @Test
    void resolve_shouldTakeWidestAmongRoles() {
        assertEquals(DataScope.TENANT, resolveWith(List.of(role("SELF"), role("TENANT")), false));
    }

    /**
     * 租户管理员能在自己租户里建角色。若不校验平台归属，任意租户建一个 ALL 角色就能越出本租户，
     * 这与超管特判额外要求平台归属是同一个道理。
     */
    @Test
    void resolve_shouldDowngradeAllToTenantForNonPlatformUser() {
        assertEquals(DataScope.TENANT, resolveWith(List.of(role("ALL")), false));
    }

    @Test
    void resolve_shouldKeepAllForPlatformOperator() {
        assertEquals(DataScope.ALL, resolveWith(List.of(role("ALL")), true));
    }

    private DataScope resolveWith(List<SysRole> roles, boolean platformOperator) {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);
            tenantSession.when(TenantSession::isPlatformOperator).thenReturn(platformOperator);
            when(userRoleResolver.enabledRolesOf(any())).thenReturn(roles);
            return resolver.resolve();
        }
    }

    private SysRole role(String dataScope) {
        SysRole role = new SysRole();
        role.setStatus(1);
        role.setDataScope(dataScope);
        return role;
    }
}
