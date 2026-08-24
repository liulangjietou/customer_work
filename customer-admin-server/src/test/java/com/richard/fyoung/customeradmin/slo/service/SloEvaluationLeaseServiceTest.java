package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloPolicyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloEvaluationLeaseServiceTest {

    private static final long NOW = Instant.parse("2026-08-24T08:30:00Z").toEpochMilli();

    private SloPolicyMapper mapper;
    private SloEvaluationLeaseService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SloPolicyMapper.class);
        SloAutomationProperties properties = new SloAutomationProperties();
        properties.setEvaluationLeaseMs(120000L);
        properties.setEvaluationIntervalMs(60000L);
        properties.setEvaluationBatchSize(20);
        service = new SloEvaluationLeaseService(mapper, properties,
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), "worker-a");
    }

    @Test
    void claimDue_shouldReturnOnlyRowsWonByLeaseCas() {
        SloPolicy first = policy(1L);
        SloPolicy second = policy(2L);
        when(mapper.findDueCandidates(NOW, 20)).thenReturn(List.of(first, second));
        when(mapper.claimEvaluation(1L, "worker-a", NOW, NOW + 120000L)).thenReturn(1);
        when(mapper.claimEvaluation(2L, "worker-a", NOW, NOW + 120000L)).thenReturn(0);

        List<SloPolicy> claimed = service.claimDue();

        assertEquals(List.of(first), claimed);
        assertEquals("worker-a", first.getEvaluationLeaseOwner());
    }

    @Test
    void complete_shouldReleaseOwnedLeaseAndScheduleNextCycle() {
        SloEvaluationVO result = mock(SloEvaluationVO.class);
        when(result.status()).thenReturn("HEALTHY");

        service.complete(policy(1L), result);

        verify(mapper).markEvaluationSuccess(eq(1L), eq("worker-a"), eq(NOW + 60000L),
            eq("HEALTHY"), any());
    }

    @Test
    void fail_shouldPersistErrorAndReleaseLeaseForRetry() {
        SloPolicy policy = policy(1L);
        policy.setEvaluationFailures(0);

        service.fail(policy, new IllegalStateException("aggregate unavailable"));

        verify(mapper).markEvaluationFailure(eq(1L), eq("worker-a"), anyLong(),
            eq("aggregate unavailable"), any());
    }

    private SloPolicy policy(Long id) {
        SloPolicy policy = new SloPolicy();
        policy.setId(id);
        policy.setTenantId("tenant-a");
        return policy;
    }
}
