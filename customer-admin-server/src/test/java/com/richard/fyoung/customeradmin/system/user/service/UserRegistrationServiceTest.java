package com.richard.fyoung.customeradmin.system.user.service;

import com.richard.fyoung.customeradmin.auth.dto.RegisterRequest;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserRegistrationServiceTest {

    private SysUserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new UserRegistrationService(userMapper, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void register_shouldCreatePendingDefaultTenantUserWithoutLeakingContext() {
        when(passwordEncoder.encode("secret12")).thenReturn("encoded");
        when(userMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            invocation.<SysUser>getArgument(0).setId(21L);
            return 1;
        });

        service.register(new RegisterRequest("richard", "secret12", "secret12", " Richard "));

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        SysUser user = userCaptor.getValue();
        assertEquals("richard", user.getUsername());
        assertEquals("Richard", user.getNickname());
        assertEquals("encoded", user.getPassword());
        assertEquals("LOCAL", user.getLoginType());
        assertEquals(1, user.getStatus());
        assertEquals(UserApprovalStatus.PENDING.name(), user.getApprovalStatus());
        assertEquals(TenantContext.DEFAULT, user.getTenantId());
        assertNull(TenantContext.get(), "公开注册结束后不能把 default 租户泄漏给复用线程");
    }

    @Test
    void register_shouldRejectPasswordConfirmationMismatchBeforeWriting() {
        BizException error = assertThrows(BizException.class, () -> service.register(
            new RegisterRequest("richard", "secret12", "secret13", "Richard")));

        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void register_shouldRejectSoftDeletedUsernameInsteadOfRevivingIt() {
        SysUser deleted = new SysUser();
        deleted.setId(9L);
        deleted.setDeleted(1);
        when(userMapper.selectByUsernameIgnoreLogicDelete("richard")).thenReturn(deleted);

        BizException error = assertThrows(BizException.class, () -> service.register(
            new RegisterRequest("richard", "secret12", "secret12", "Richard")));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, error.getResultCode());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void register_shouldTranslateConcurrentUniqueKeyRaceToBusinessError() {
        when(passwordEncoder.encode("secret12")).thenReturn("encoded");
        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("duplicate"));

        BizException error = assertThrows(BizException.class, () -> service.register(
            new RegisterRequest("richard", "secret12", "secret12", "Richard")));

        assertEquals(ResultCode.RESOURCE_DUPLICATE, error.getResultCode());
        assertEquals("用户名已存在", error.getMessage());
    }
}
