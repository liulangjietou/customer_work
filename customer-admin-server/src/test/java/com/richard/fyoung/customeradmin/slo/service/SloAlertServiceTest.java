package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.slo.domain.SloAlertEventType;
import com.richard.fyoung.customeradmin.slo.domain.SloAlertStatus;
import com.richard.fyoung.customeradmin.slo.dto.SloWindowEvaluation;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloAlertEvent;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertEventMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloAlertServiceTest {

    private SloAlertMapper alertMapper;
    private SloAlertEventMapper eventMapper;
    private SloPolicyMapper policyMapper;
    private SloNotificationService notificationService;
    private SloAlertService service;

    @BeforeEach
    void setUp() {
        alertMapper = mock(SloAlertMapper.class);
        eventMapper = mock(SloAlertEventMapper.class);
        policyMapper = mock(SloPolicyMapper.class);
        notificationService = mock(SloNotificationService.class);
        service = new SloAlertService(alertMapper, eventMapper, policyMapper, notificationService);
    }

    @Test
    void reconcile_shouldOpenOneActiveAlertAndEnqueueEventAtomically() {
        SloPolicy policy = policy();
        when(alertMapper.findActiveForUpdate("tenant-a", 7L)).thenReturn(null);
        when(alertMapper.insertIgnore(any())).thenAnswer(invocation -> {
            SloAlert alert = invocation.getArgument(0);
            alert.setId(101L);
            return 1;
        });
        when(eventMapper.insert(any(SloAlertEvent.class))).thenAnswer(invocation -> {
            SloAlertEvent event = invocation.getArgument(0);
            event.setId(201L);
            return 1;
        });
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 8, 30);

        SloAlertService.Transition transition = service.reconcile(policy, "tenant-a", now,
            true, false, window("4.000000"), window("3.000000"), 12345L);

        assertEquals(SloAlertService.Transition.OPENED, transition);
        ArgumentCaptor<SloAlert> alertCaptor = ArgumentCaptor.forClass(SloAlert.class);
        verify(alertMapper).insertIgnore(alertCaptor.capture());
        assertEquals(SloAlertStatus.OPEN.name(), alertCaptor.getValue().getStatus());
        assertEquals(7L, alertCaptor.getValue().getActivePolicyId());
        ArgumentCaptor<SloAlertEvent> eventCaptor = ArgumentCaptor.forClass(SloAlertEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(SloAlertEventType.OPENED.name(), eventCaptor.getValue().getEventType());
        verify(notificationService).enqueue(eq(eventCaptor.getValue()), eq(alertCaptor.getValue()), eq(policy));
    }

    @Test
    void reconcile_shouldRefreshExistingAckedAlertWithoutDuplicateEvent() {
        SloAlert active = alert(101L, SloAlertStatus.ACKED);
        when(alertMapper.findActiveForUpdate("tenant-a", 7L)).thenReturn(active);

        SloAlertService.Transition transition = service.reconcile(policy(), "tenant-a",
            LocalDateTime.of(2026, 8, 24, 8, 31), true, false,
            window("5.000000"), window("4.000000"), 12346L);

        assertEquals(SloAlertService.Transition.NONE, transition);
        verify(alertMapper).updateActiveSeen(eq(101L), eq(new BigDecimal("5.000000")),
            eq(new BigDecimal("4.000000")), any());
        verify(eventMapper, never()).insert(any(SloAlertEvent.class));
    }

    @Test
    void reconcile_shouldResolveAckedAlertAndEmitRecoveryEvent() {
        SloAlert active = alert(101L, SloAlertStatus.ACKED);
        when(alertMapper.findActiveForUpdate("tenant-a", 7L)).thenReturn(active);
        when(alertMapper.resolve(eq(101L), any(), any(), any())).thenReturn(1);
        when(eventMapper.insert(any(SloAlertEvent.class))).thenAnswer(invocation -> {
            SloAlertEvent event = invocation.getArgument(0);
            event.setId(202L);
            return 1;
        });

        SloAlertService.Transition transition = service.reconcile(policy(), "tenant-a",
            LocalDateTime.of(2026, 8, 24, 8, 32), false, true,
            window("0.500000"), window("0.400000"), 12347L);

        assertEquals(SloAlertService.Transition.RESOLVED, transition);
        assertEquals(SloAlertStatus.RESOLVED.name(), active.getStatus());
        assertNull(active.getActivePolicyId());
        ArgumentCaptor<SloAlertEvent> captor = ArgumentCaptor.forClass(SloAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(SloAlertEventType.RESOLVED.name(), captor.getValue().getEventType());
        verify(notificationService).enqueue(eq(captor.getValue()), eq(active), any());
    }

    @Test
    void acknowledge_shouldTransitionOpenAlertAndPersistAckEvent() {
        SloAlert open = alert(101L, SloAlertStatus.OPEN);
        when(alertMapper.selectOne(any())).thenReturn(open);
        when(alertMapper.acknowledge(eq(101L), eq("tenant-a"), eq(9L), any())).thenReturn(1);
        when(policyMapper.selectOne(any())).thenReturn(policy());
        when(eventMapper.insert(any(SloAlertEvent.class))).thenAnswer(invocation -> {
            SloAlertEvent event = invocation.getArgument(0);
            event.setId(203L);
            return 1;
        });

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            service.acknowledge(101L, 9L);
        }

        ArgumentCaptor<SloAlertEvent> captor = ArgumentCaptor.forClass(SloAlertEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(SloAlertEventType.ACKED.name(), captor.getValue().getEventType());
        assertEquals(9L, captor.getValue().getActorUserId());
        verify(notificationService).enqueue(eq(captor.getValue()), eq(open), any());
    }

    @Test
    void acknowledge_shouldRejectResolvedAlert() {
        when(alertMapper.selectOne(any())).thenReturn(alert(101L, SloAlertStatus.RESOLVED));
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.acknowledge(101L, 9L));
        }
        verify(eventMapper, never()).insert(any(SloAlertEvent.class));
    }

    @Test
    void summary_shouldCountAllActiveAlertsByTenantAndStatus() {
        when(alertMapper.selectCount(any())).thenReturn(203L, 17L);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            var summary = service.summary();

            assertEquals(203L, summary.openCount());
            assertEquals(17L, summary.acknowledgedCount());
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SloAlert>> captor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(alertMapper, times(2)).selectCount(captor.capture());
        captor.getAllValues().forEach(wrapper -> {
            String sql = wrapper.getExpression().getNormal().getSqlSegment();
            assertTrue(sql.contains("tenant_id ="));
            assertTrue(sql.contains("status ="));
            assertTrue(wrapper.getParamNameValuePairs().containsValue("tenant-a"));
        });
        assertTrue(captor.getAllValues().get(0).getParamNameValuePairs()
            .containsValue(SloAlertStatus.OPEN.name()));
        assertTrue(captor.getAllValues().get(1).getParamNameValuePairs()
            .containsValue(SloAlertStatus.ACKED.name()));
    }

    private SloPolicy policy() {
        SloPolicy policy = new SloPolicy();
        policy.setId(7L);
        policy.setTenantId("tenant-a");
        policy.setPolicyName("客服 SLO");
        policy.setScopeType("AGENT");
        policy.setScopeKey("support-agent");
        return policy;
    }

    private SloAlert alert(long id, SloAlertStatus status) {
        SloAlert alert = new SloAlert();
        alert.setId(id);
        alert.setTenantId("tenant-a");
        alert.setPolicyId(7L);
        alert.setActivePolicyId(status == SloAlertStatus.RESOLVED ? null : 7L);
        alert.setStatus(status.name());
        alert.setShortBurnRate(new BigDecimal("4.000000"));
        alert.setLongBurnRate(new BigDecimal("3.000000"));
        return alert;
    }

    private SloWindowEvaluation window(String burnRate) {
        return new SloWindowEvaluation(5, 100, 90, 10, 90, 90,
            new BigDecimal("0.900000"), new BigDecimal("0.900000"),
            BigDecimal.ZERO, new BigDecimal(burnRate));
    }
}
