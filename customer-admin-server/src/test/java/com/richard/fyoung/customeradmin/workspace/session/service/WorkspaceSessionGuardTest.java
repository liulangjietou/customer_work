package com.richard.fyoung.customeradmin.workspace.session.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.datascope.DataScope;
import com.richard.fyoung.customeradmin.datascope.DataScopeContext;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.session.entity.WorkspaceSession;
import com.richard.fyoung.customeradmin.workspace.session.mapper.WorkspaceSessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作区根资源认领测试：历史状态不能由第一个猜中 sessionId 的用户接管；
 * 以及归属校验在不同数据范围下的放行边界。
 */
class WorkspaceSessionGuardTest {

    private WorkspaceSessionMapper mapper;
    private WorkspaceSessionGuard guard;

    @BeforeEach
    void setUp() {
        mapper = mock(WorkspaceSessionMapper.class);
        guard = new WorkspaceSessionGuard(mapper, new AdminTenantProperties());
    }

    @Test
    void claimOrRequire_shouldRejectUnownedLegacyState() {
        when(mapper.countState("coder", "legacy-session")).thenReturn(1);

        assertThrows(BizException.class,
            () -> guard.claimOrRequire("coder", "legacy-session", 7L));

        verify(mapper, never()).insertIgnore(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), anyLong());
    }

    @Test
    void claimOrRequire_shouldRejectDifferentOwner() {
        WorkspaceSession existing = new WorkspaceSession();
        existing.setOwnerUserId(8L);
        when(mapper.findByResource("default", "coder", "s1")).thenReturn(existing);

        assertThrows(BizException.class, () -> guard.claimOrRequire("coder", "s1", 7L));
        verify(mapper, never()).countState("coder", "s1");
    }

    /** 超管与运营方（ALL）要能看全量，归属校验对他们只校验会话存在。 */
    @Test
    void requireOwned_shouldAllowOthersSession_whenScopeIsAll() {
        WorkspaceSession existing = new WorkspaceSession();
        existing.setOwnerUserId(8L);
        when(mapper.findByResource("default", "coder", "s1")).thenReturn(existing);
        DataScopeContext.set(DataScope.ALL, 7L);

        assertDoesNotThrow(() -> guard.requireOwned("coder", "s1", 7L));
        assertTrue(guard.isOwned("coder", "s1", 7L));
    }

    /** 租户管理员（TENANT）同样放行，租户边界仍由 currentTenant 守着。 */
    @Test
    void requireOwned_shouldAllowOthersSession_whenScopeIsTenant() {
        WorkspaceSession existing = new WorkspaceSession();
        existing.setOwnerUserId(8L);
        when(mapper.findByResource("default", "coder", "s1")).thenReturn(existing);
        DataScopeContext.set(DataScope.TENANT, 7L);

        assertDoesNotThrow(() -> guard.requireOwned("coder", "s1", 7L));
    }

    /**
     * 放宽必须有明确依据：SELF 与"压根没有上下文"都要按原样严格校验。
     *
     * <p>这两种情形在 {@code restrictedUserId()} 上看起来一样（都非空/都为空各占一边），
     * 用它来判断放行会让脱离登录态的调用直接读到别人的会话，因此这里走 {@code relaxedBeyondSelf()}。</p>
     */
    @Test
    void requireOwned_shouldRejectOthersSession_whenScopeIsSelfOrAbsent() {
        WorkspaceSession existing = new WorkspaceSession();
        existing.setOwnerUserId(8L);
        when(mapper.findByResource("default", "coder", "s1")).thenReturn(existing);

        DataScopeContext.set(DataScope.SELF, 7L);
        assertThrows(BizException.class, () -> guard.requireOwned("coder", "s1", 7L));

        DataScopeContext.clear();
        assertThrows(BizException.class, () -> guard.requireOwned("coder", "s1", 7L));
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
    }
}
