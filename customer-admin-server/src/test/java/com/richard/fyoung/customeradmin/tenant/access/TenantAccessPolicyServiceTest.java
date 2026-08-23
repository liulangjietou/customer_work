package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantAccessPolicyServiceTest {

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final TenantAccessPolicyService service = new TenantAccessPolicyService(userMapper, tenantService);

    @Test
    void validUserAndTenantEpochs_shouldPass() {
        when(userMapper.selectById(9L)).thenReturn(user(1, 3L));
        when(tenantService.requireAccessibleSnapshot("acme"))
            .thenReturn(new TenantAccessSnapshot("acme", "ACTIVE", 5L, null));

        assertDoesNotThrow(() -> service.assertUserSessionAccessible(9L, "acme", 3L, 5L));
    }

    @Test
    void disabledUser_shouldExpireSession() {
        when(userMapper.selectById(9L)).thenReturn(user(0, 3L));

        BizException exception = assertThrows(BizException.class,
            () -> service.assertUserSessionAccessible(9L, "acme", 3L, 5L));

        assertEquals(ResultCode.TOKEN_EXPIRED, exception.getResultCode());
    }

    @Test
    void changedAuthEpoch_shouldExpireSession() {
        when(userMapper.selectById(9L)).thenReturn(user(1, 4L));

        BizException exception = assertThrows(BizException.class,
            () -> service.assertUserSessionAccessible(9L, "acme", 3L, 5L));

        assertEquals(ResultCode.TOKEN_EXPIRED, exception.getResultCode());
    }

    @Test
    void changedTenantEpoch_shouldExpireSession() {
        when(userMapper.selectById(9L)).thenReturn(user(1, 3L));
        when(tenantService.requireAccessibleSnapshot("acme"))
            .thenReturn(new TenantAccessSnapshot("acme", "ACTIVE", 6L, null));

        BizException exception = assertThrows(BizException.class,
            () -> service.assertUserSessionAccessible(9L, "acme", 3L, 5L));

        assertEquals(ResultCode.TOKEN_EXPIRED, exception.getResultCode());
    }

    private SysUser user(int status, long authEpoch) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setTenantId("acme");
        user.setStatus(status);
        user.setAuthEpoch(authEpoch);
        return user;
    }
}
