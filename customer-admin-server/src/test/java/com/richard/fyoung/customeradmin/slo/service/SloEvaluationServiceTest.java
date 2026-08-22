package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloAlertMapper;
import com.richard.fyoung.customeradmin.slo.mapper.SloCallAggregate;
import com.richard.fyoung.customeradmin.slo.mapper.SloCallAggregateMapper;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloEvaluationServiceTest {

    private SloPolicyService policyService;
    private SloCallAggregateMapper aggregateMapper;
    private SloAlertMapper alertMapper;
    private SloEvaluationService service;

    @BeforeEach
    void setUp() {
        policyService = mock(SloPolicyService.class);
        aggregateMapper = mock(SloCallAggregateMapper.class);
        alertMapper = mock(SloAlertMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T08:30:45Z"), ZoneOffset.UTC);
        service = new SloEvaluationService(policyService, aggregateMapper, alertMapper, clock);
    }

    @Test
    void evaluate_shouldUseRealSuccessAndLatencyCountsForHealthyWindows() {
        SloPolicy policy = policy("TENANT", null);
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);
        when(aggregateMapper.aggregate(eq("tenant-a"), eq(null), anyLong(), anyLong(), eq(3000L)))
            .thenReturn(aggregate(100, 100, 98, 98));

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            SloEvaluationVO result = service.evaluate(7L);

            assertEquals("HEALTHY", result.status());
            assertEquals(100, result.shortWindow().total());
            assertEquals(98, result.shortWindow().good());
            assertEquals(2, result.shortWindow().bad());
            assertEquals(new BigDecimal("0.980000"), result.shortWindow().latencyRatio());
            assertEquals(new BigDecimal("0.400000"), result.shortWindow().burnRate());
            assertEquals(100, result.minimumSampleCount());
            assertFalse(result.alertCreated());
            verify(alertMapper, never()).insertIgnore(any());
        }
    }

    @Test
    void evaluate_shouldCreateOneMinuteBucketAlertWhenBothWindowsBurn() {
        SloPolicy policy = policy("AGENT", "support-agent");
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);
        when(aggregateMapper.aggregate(eq("tenant-a"), eq("support-agent"), anyLong(), anyLong(), eq(3000L)))
            .thenReturn(aggregate(100, 90, 90, 85));
        when(alertMapper.insertIgnore(any())).thenReturn(1);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            SloEvaluationVO result = service.evaluate(7L);

            assertEquals("BURNING", result.status());
            assertTrue(result.alertCreated());
            ArgumentCaptor<SloAlert> captor = ArgumentCaptor.forClass(SloAlert.class);
            verify(alertMapper).insertIgnore(captor.capture());
            assertEquals("tenant-a", captor.getValue().getTenantId());
            assertEquals(7L, captor.getValue().getPolicyId());
            assertEquals(Instant.parse("2026-08-22T08:30:45Z").getEpochSecond() / 60,
                captor.getValue().getWindowEndMinute());
        }
    }

    @Test
    void evaluate_shouldExposeDeduplicatedAlertFact() {
        SloPolicy policy = policy("TENANT", null);
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);
        when(aggregateMapper.aggregate(any(), any(), anyLong(), anyLong(), anyLong()))
            .thenReturn(aggregate(100, 0, 0, 0));
        when(alertMapper.insertIgnore(any())).thenReturn(0);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            SloEvaluationVO result = service.evaluate(7L);
            assertEquals("BURNING", result.status());
            assertFalse(result.alertCreated());
        }
    }

    @Test
    void evaluate_shouldNotAlertWhenEitherWindowHasTooFewSamples() {
        SloPolicy policy = policy("TENANT", null);
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);
        when(aggregateMapper.aggregate(any(), any(), anyLong(), anyLong(), anyLong()))
            .thenReturn(aggregate(99, 0, 0, 0), aggregate(100, 0, 0, 0));

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            SloEvaluationVO result = service.evaluate(7L);

            assertEquals("INSUFFICIENT_DATA", result.status());
            assertEquals(99, result.shortWindow().total());
            assertEquals(100, result.longWindow().total());
            assertEquals(100, result.minimumSampleCount());
            assertFalse(result.alertCreated());
            verify(alertMapper, never()).insertIgnore(any());
        }
    }

    @Test
    void evaluate_shouldResolveChannelInsideSameTenantBeforeAggregation() {
        SloPolicy policy = policy("CHANNEL", "web");
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);
        when(aggregateMapper.findAgentCodeByChannel("tenant-a", "web")).thenReturn("support-agent");
        when(aggregateMapper.aggregate(eq("tenant-a"), eq("support-agent"), anyLong(), anyLong(), eq(3000L)))
            .thenReturn(aggregate(0, 0, 0, 0));

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            SloEvaluationVO result = service.evaluate(7L);
            assertEquals("NO_DATA", result.status());
            assertEquals(new BigDecimal("0.000000"), result.shortWindow().burnRate());
            verify(aggregateMapper).findAgentCodeByChannel("tenant-a", "web");
        }
    }

    @Test
    void evaluate_shouldRejectChannelWithoutActiveTenantBinding() {
        SloPolicy policy = policy("CHANNEL", "unknown");
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.evaluate(7L));
            verify(aggregateMapper, never()).aggregate(any(), any(), anyLong(), anyLong(), anyLong());
        }
    }

    @Test
    void evaluate_shouldRejectDisabledPolicy() {
        SloPolicy policy = policy("TENANT", null);
        policy.setEnabled(false);
        when(policyService.requirePolicy(7L, "tenant-a")).thenReturn(policy);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.evaluate(7L));
        }
    }

    private SloPolicy policy(String scopeType, String scopeKey) {
        SloPolicy policy = new SloPolicy();
        policy.setId(7L);
        policy.setPolicyName("客服 SLO");
        policy.setScopeType(scopeType);
        policy.setScopeKey(scopeKey);
        policy.setAvailabilityTarget(new BigDecimal("0.99"));
        policy.setLatencyTarget(new BigDecimal("0.95"));
        policy.setLatencyThresholdMs(3000L);
        policy.setShortWindowMinutes(5);
        policy.setLongWindowMinutes(60);
        policy.setMinimumSampleCount(100);
        policy.setBurnRateThreshold(new BigDecimal("2"));
        policy.setEnabled(true);
        return policy;
    }

    private SloCallAggregate aggregate(long total, long availabilityGood, long latencyGood,
                                       long compositeGood) {
        SloCallAggregate result = new SloCallAggregate();
        result.setTotal(total);
        result.setAvailabilityGood(availabilityGood);
        result.setLatencyGood(latencyGood);
        result.setCompositeGood(compositeGood);
        return result;
    }
}
