package com.richard.fyoung.customeradmin.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户名唯一约束冲突的友好错误回归测试。 */
class UserServiceDuplicateUsernameTest {

    private SysUserMapper userMapper;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("encoded");
        service = new UserService(
            userMapper,
            mock(SysUserRoleMapper.class),
            mock(SysRoleMapper.class),
            passwordEncoder,
            mock(CrossTenantAuthority.class),
            mock(SessionRevocationService.class));
    }

    @Test
    void create_shouldReturnFriendlyErrorWhenUsernameAlreadyExists() {
        when(userMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(true);

        BizException exception = assertThrows(BizException.class,
            () -> service.create(request("richard")));

        assertDuplicateUsername(exception);
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void create_shouldReturnFriendlyErrorWhenDatabaseUniqueConstraintWins() {
        when(userMapper.exists(any(LambdaQueryWrapper.class))).thenReturn(false);
        when(userMapper.insert(any(SysUser.class)))
            .thenThrow(new DuplicateKeyException("sys_user.uk_sys_user_username"));

        BizException exception = assertThrows(BizException.class,
            () -> service.create(request("richard")));

        assertDuplicateUsername(exception);
        verify(userMapper).insert(any(SysUser.class));
    }

    private void assertDuplicateUsername(BizException exception) {
        assertEquals(ResultCode.RESOURCE_DUPLICATE, exception.getResultCode());
        assertEquals("用户名已存在", exception.getMessage());
    }

    private UserSaveRequest request(String username) {
        return new UserSaveRequest(username, "password", "测试用户", 1, List.of());
    }
}
