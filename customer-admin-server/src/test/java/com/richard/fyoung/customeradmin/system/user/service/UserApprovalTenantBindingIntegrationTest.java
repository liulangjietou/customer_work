package com.richard.fyoung.customeradmin.system.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.auth.service.SessionRevocationService;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.system.role.entity.SysRole;
import com.richard.fyoung.customeradmin.system.role.mapper.SysRoleMapper;
import com.richard.fyoung.customeradmin.system.user.domain.UserApprovalStatus;
import com.richard.fyoung.customeradmin.system.user.dto.UserApprovalRequest;
import com.richard.fyoung.customeradmin.system.user.entity.SysUser;
import com.richard.fyoung.customeradmin.system.user.entity.SysUserRole;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserRoleMapper;
import com.richard.fyoung.customeradmin.tenant.CrossTenantAuthority;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.service.TenantService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户审核绑定租户的真 MySQL 行级隔离回归。 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class UserApprovalTenantBindingIntegrationTest {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @BeforeAll
    static void checkMysqlReachable() {
        assumeTrue(reachable(), "MySQL 不可达，跳过用户审核租户绑定真库测试");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void review_shouldMoveUserAndRoleRelationAcrossTenantBoundaries() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String targetTenant = "approval_" + suffix;
        SysUser user = pendingUser("approval_user_" + suffix);
        TenantContext.runWith(TenantContext.DEFAULT, () -> userMapper.insert(user));

        SysRole targetRole = tenantRole("approval_role_" + suffix);
        TenantContext.runWith(targetTenant, () -> roleMapper.insert(targetRole));

        CrossTenantAuthority crossTenantAuthority = mock(CrossTenantAuthority.class);
        SessionRevocationService revocationService = mock(SessionRevocationService.class);
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.resolveAccessibleSnapshot(targetTenant))
            .thenReturn(new TenantAccessSnapshot(targetTenant, "ACTIVE", 0L, null));
        UserService service = new UserService(
            userMapper,
            userRoleMapper,
            roleMapper,
            mock(PasswordEncoder.class),
            crossTenantAuthority,
            revocationService,
            tenantService);

        try (MockedStatic<StpUtil> stpUtil = org.mockito.Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            TenantContext.runWith(TenantContext.DEFAULT, () -> service.review(user.getId(),
                new UserApprovalRequest(
                    UserApprovalStatus.APPROVED, targetTenant, List.of(targetRole.getId()), "真库核验")));
        }

        TenantContext.runWith(TenantContext.DEFAULT, () -> {
            assertNull(userMapper.selectById(user.getId()), "用户迁移后源租户必须不可见");
            assertTrue(userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, user.getId())).isEmpty(), "源租户不能残留角色关系");
        });
        TenantContext.runWith(targetTenant, () -> {
            SysUser moved = userMapper.selectById(user.getId());
            assertEquals(targetTenant, moved.getTenantId());
            assertEquals(UserApprovalStatus.APPROVED.name(), moved.getApprovalStatus());
            assertEquals("真库核验", moved.getApprovalRemark());
            assertEquals(1L, moved.getAuthEpoch());
            List<SysUserRole> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, user.getId()));
            assertEquals(List.of(targetRole.getId()),
                relations.stream().map(SysUserRole::getRoleId).toList());
        });
        verify(crossTenantAuthority).requireCurrentUserAuthority();
        verify(revocationService).revokeUserAfterCommit(user.getId());
    }

    private SysUser pendingUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setTenantId(TenantContext.DEFAULT);
        user.setPassword("$2a$10$integration.test.hash");
        user.setNickname("待审核用户");
        user.setStatus(1);
        user.setApprovalStatus(UserApprovalStatus.PENDING.name());
        return user;
    }

    private SysRole tenantRole(String roleCode) {
        SysRole role = new SysRole();
        role.setRoleName("审核测试角色");
        role.setRoleCode(roleCode);
        role.setStatus(1);
        role.setDataScope(DataScope.TENANT.name());
        role.setControlPlane(SysRole.CONTROL_PLANE_DISABLED);
        return role;
    }

    private static boolean reachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3306), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
