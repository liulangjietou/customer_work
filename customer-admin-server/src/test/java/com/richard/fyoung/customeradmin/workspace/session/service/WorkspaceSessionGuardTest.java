package com.richard.fyoung.customeradmin.workspace.session.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customeradmin.workspace.session.entity.WorkspaceSession;
import com.richard.fyoung.customeradmin.workspace.session.mapper.WorkspaceSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工作区根资源认领测试：历史状态不能由第一个猜中 sessionId 的用户接管。 */
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
}
