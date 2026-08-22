package com.richard.fyoung.customeradmin.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.richard.fyoung.customeradmin.system.user.mapper.SysUserMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRevocationServiceTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TenantContext.clear();
    }

    @Test
    void revokeUser_shouldWaitUntilTransactionCommits() {
        SessionRevocationService service = new SessionRevocationService(mock(SysUserMapper.class));
        TransactionSynchronizationManager.initSynchronization();

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            service.revokeUserAfterCommit(7L);
            stpUtil.verify(() -> StpUtil.logout(7L), never());

            TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

            stpUtil.verify(() -> StpUtil.logout(7L));
        }
    }

    @Test
    void revokeTenant_shouldLogoutEveryUserInsideTargetTenant() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        when(userMapper.selectUserIdsForSessionRevocation()).thenReturn(List.of(1L, 2L));
        SessionRevocationService service = new SessionRevocationService(userMapper);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            service.revokeTenantAfterCommit("acme");

            verify(userMapper).selectUserIdsForSessionRevocation();
            stpUtil.verify(() -> StpUtil.logout(1L));
            stpUtil.verify(() -> StpUtil.logout(2L));
        }
    }
}
