package com.richard.fyoung.customeradmin.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.notify.RegistrationNotificationService;
import com.richard.fyoung.customeradmin.publicdeploy.PublicDeploymentProperties;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.dto.UserSaveRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceRevocationTest {

    private SysUserMapper userMapper;
    private SysUserRoleMapper userRoleMapper;
    private SessionRevocationService revocationService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        userRoleMapper = mock(SysUserRoleMapper.class);
        revocationService = mock(SessionRevocationService.class);
        service = new UserService(
            userMapper,
            userRoleMapper,
            mock(SysRoleMapper.class),
            mock(PasswordEncoder.class),
            mock(CrossTenantAuthority.class),
            revocationService,
            mock(TenantService.class),
            new PublicDeploymentProperties(),
            mock(RegistrationNotificationService.class));
        when(userRoleMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(userMapper.incrementAuthEpoch(9L)).thenReturn(1);
    }

    @Test
    void disablingUser_shouldRotateEpochAndRevokeSessions() {
        when(userMapper.selectById(9L)).thenReturn(user(1));

        service.update(9L, request("昵称", 0));

        verify(userMapper).incrementAuthEpoch(9L);
        verify(revocationService).revokeUserAfterCommit(9L);
    }

    @Test
    void nicknameOnlyChange_shouldNotRevokeSessions() {
        when(userMapper.selectById(9L)).thenReturn(user(1));

        service.update(9L, request("新昵称", 1));

        verify(userMapper, never()).incrementAuthEpoch(9L);
        verify(revocationService, never()).revokeUserAfterCommit(9L);
    }

    @Test
    void deletingUser_shouldRotateEpochBeforeLogicalDeleteAndRevoke() {
        when(userMapper.selectById(9L)).thenReturn(user(1));

        service.delete(9L);

        verify(userMapper).incrementAuthEpoch(9L);
        verify(userMapper).deleteById(9L);
        verify(revocationService).revokeUserAfterCommit(9L);
    }

    private UserSaveRequest request(String nickname, int status) {
        return new UserSaveRequest("richard", "", nickname, status, List.of());
    }

    private SysUser user(int status) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername("richard");
        user.setNickname("旧昵称");
        user.setStatus(status);
        return user;
    }
}
