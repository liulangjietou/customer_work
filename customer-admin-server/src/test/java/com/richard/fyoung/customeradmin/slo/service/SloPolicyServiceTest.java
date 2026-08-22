package com.richard.fyoung.customeradmin.slo.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.slo.dto.SloPolicySaveRequest;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloPolicyServiceTest {

    private SloPolicyMapper mapper;
    private SloPolicyService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SloPolicyMapper.class);
        service = new SloPolicyService(mapper);
    }

    @Test
    void upsert_shouldForceCurrentTenantAndNormalizeTenantScope() {
        when(mapper.insert(any(SloPolicy.class))).thenAnswer(invocation -> {
            SloPolicy p = invocation.getArgument(0);
            p.setId(11L);
            return 1;
        });
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            Long id = service.upsert(request(null, "tenant", "ignored", 5, 60));

            assertEquals(11L, id);
            ArgumentCaptor<SloPolicy> captor = ArgumentCaptor.forClass(SloPolicy.class);
            verify(mapper).insert(captor.capture());
            assertEquals("tenant-a", captor.getValue().getTenantId());
            assertEquals("TENANT", captor.getValue().getScopeType());
            assertNull(captor.getValue().getScopeKey());
            assertEquals(100, captor.getValue().getMinimumSampleCount());
        }
    }

    @Test
    void upsert_shouldPersistConfiguredMinimumSampleCount() {
        when(mapper.insert(any(SloPolicy.class))).thenReturn(1);
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            service.upsert(request(null, "TENANT", null, 5, 60, 250));

            ArgumentCaptor<SloPolicy> captor = ArgumentCaptor.forClass(SloPolicy.class);
            verify(mapper).insert(captor.capture());
            assertEquals(250, captor.getValue().getMinimumSampleCount());
        }
    }

    @Test
    void upsert_shouldPreserveExistingMinimumWhenLegacyClientOmitsIt() {
        SloPolicy existing = new SloPolicy();
        existing.setId(7L);
        existing.setMinimumSampleCount(250);
        when(mapper.selectOne(any())).thenReturn(existing);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            service.upsert(request(7L, "TENANT", null, 5, 60));

            ArgumentCaptor<SloPolicy> policyCaptor = ArgumentCaptor.forClass(SloPolicy.class);
            @SuppressWarnings("rawtypes")
            ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(mapper).update(policyCaptor.capture(), wrapperCaptor.capture());
            assertEquals(250, policyCaptor.getValue().getMinimumSampleCount());
            assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("tenant_id"));
        }
    }

    @Test
    void upsert_shouldRejectMissingAgentScopeKey() {
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.upsert(request(null, "AGENT", " ", 5, 60)));
            verify(mapper, never()).insert(any(SloPolicy.class));
        }
    }

    @Test
    void upsert_shouldRejectOverlappingWindowDefinition() {
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.upsert(request(null, "TENANT", null, 60, 60)));
        }
    }

    @Test
    void requirePolicy_shouldUseExplicitTenantPredicate() {
        SloPolicy policy = new SloPolicy();
        policy.setId(7L);
        when(mapper.selectOne(any())).thenReturn(policy);

        assertEquals(policy, service.requirePolicy(7L, "tenant-a"));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("tenant_id"));
    }

    private SloPolicySaveRequest request(Long id, String scopeType, String scopeKey,
                                         int shortWindow, int longWindow) {
        return request(id, scopeType, scopeKey, shortWindow, longWindow, null);
    }

    private SloPolicySaveRequest request(Long id, String scopeType, String scopeKey,
                                         int shortWindow, int longWindow,
                                         Integer minimumSampleCount) {
        return new SloPolicySaveRequest(id, "客服 SLO", scopeType, scopeKey,
            new BigDecimal("0.99"), new BigDecimal("0.95"), 3000L,
            shortWindow, longWindow, minimumSampleCount, new BigDecimal("2"), true);
    }
}
