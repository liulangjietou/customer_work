package com.richard.fyoung.customeradmin.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.service.UserRoleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link CrossTenantAuthority} 的授权正反测试。 */
class CrossTenantAuthorityTest {

    private UserRoleResolver userRoleResolver;
    private CrossTenantAuthority authority;

    @BeforeEach
    void setUp() {
        userRoleResolver = mock(UserRoleResolver.class);
        authority = new CrossTenantAuthority(userRoleResolver);
    }

    @Test
    void hasAuthority_shouldOnlyAcceptExplicitControlPlaneFlag() {
        SysRole ordinaryRole = role("super_admin", 0);
        SysRole controlPlaneRole = role("operator", SysRole.CONTROL_PLANE_ENABLED);

        assertFalse(authority.hasAuthority(List.of(ordinaryRole)),
            "角色编码不能替代 control_plane 授权");
        assertTrue(authority.hasAuthority(List.of(ordinaryRole, controlPlaneRole)));
    }

    @Test
    void hasCurrentUserAuthority_shouldDenyWhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            assertFalse(authority.hasCurrentUserAuthority());
            verifyNoInteractions(userRoleResolver);
        }
    }

    @Test
    void hasCurrentUserAuthority_shouldResolveRolesInsteadOfTenantId() {
        long userId = 7L;
        when(userRoleResolver.enabledRolesOf(userId))
            .thenReturn(List.of(role("custom_control_role", SysRole.CONTROL_PLANE_ENABLED)));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(userId);

            assertTrue(authority.hasCurrentUserAuthority());
        }
    }

    @Test
    void requireCurrentUserAuthority_shouldFailFastWhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            BizException exception = assertThrows(BizException.class,
                authority::requireCurrentUserAuthority);

            assertEquals(ResultCode.TENANT_VIEW_FORBIDDEN, exception.getResultCode());
        }
    }

    private SysRole role(String roleCode, int controlPlane) {
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setStatus(1);
        role.setControlPlane(controlPlane);
        return role;
    }
}
