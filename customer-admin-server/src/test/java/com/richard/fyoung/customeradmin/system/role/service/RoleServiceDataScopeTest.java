package com.richard.fyoung.customeradmin.system.role.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.system.permission.mapper.SysPermissionMapper;
import com.richard.fyoung.customeradmin.system.role.dto.RoleSaveRequest;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRolePermissionMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RoleService} 的数据范围相关单测：落库归一化与"ALL 仅平台运营方"的服务端校验。
 * @author owlzhangfq@gmail.com
 */
class RoleServiceDataScopeTest {

    private SysRoleMapper roleMapper;
    private RoleService service;

    @BeforeEach
    void setUp() {
        roleMapper = mock(SysRoleMapper.class);
        service = new RoleService(roleMapper, mock(SysRolePermissionMapper.class), mock(SysPermissionMapper.class));
        when(roleMapper.exists(any())).thenReturn(false);
    }

    /** 不传范围时按最小权限落 SELF，而不是留空由 SQL 默认值兜底——留空会让"是否设过"变得不可知。 */
    @Test
    void create_shouldDefaultToSelfWhenScopeAbsent() {
        SysRole saved = createWith(null, false);
        assertEquals(DataScope.SELF.name(), saved.getDataScope());
    }

    @Test
    void create_shouldNormalizeUnknownScopeToSelf() {
        SysRole saved = createWith("EVERYTHING", false);
        assertEquals(DataScope.SELF.name(), saved.getDataScope());
    }

    @Test
    void create_shouldAcceptTenantScopeForNonPlatformUser() {
        SysRole saved = createWith("TENANT", false);
        assertEquals(DataScope.TENANT.name(), saved.getDataScope());
    }

    /**
     * 租户管理员能建角色，若让他把角色设成 ALL 就等于自己给自己开跨租户的口子。
     * 前端已隐藏该选项，但那只是体验——越权判定必须收在服务端。
     */
    @Test
    void create_shouldRejectAllScopeFromNonPlatformUser() {
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::isPlatformOperator).thenReturn(false);

            BizException ex = assertThrows(BizException.class,
                () -> service.create(request("ALL")));

            assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        }
    }

    @Test
    void create_shouldAllowAllScopeForPlatformOperator() {
        SysRole saved = createWith("ALL", true);
        assertEquals(DataScope.ALL.name(), saved.getDataScope());
    }

    private SysRole createWith(String dataScope, boolean platformOperator) {
        try (MockedStatic<TenantSession> tenantSession = mockStatic(TenantSession.class)) {
            tenantSession.when(TenantSession::isPlatformOperator).thenReturn(platformOperator);
            service.create(request(dataScope));
        }
        ArgumentCaptor<SysRole> captor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(captor.capture());
        return captor.getValue();
    }

    private RoleSaveRequest request(String dataScope) {
        return new RoleSaveRequest("测试角色", "test_role", null, 1, dataScope, List.of());
    }
}
