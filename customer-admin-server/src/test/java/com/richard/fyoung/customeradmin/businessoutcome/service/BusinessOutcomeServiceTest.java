package com.richard.fyoung.customeradmin.businessoutcome.service;

import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeAggregateRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionPageVO;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSessionRow;
import com.richard.fyoung.customeradmin.businessoutcome.dto.BusinessOutcomeSummaryVO;
import com.richard.fyoung.customeradmin.businessoutcome.gateway.BusinessOutcomeGateway;
import com.richard.fyoung.customeradmin.businessoutcome.gateway.BusinessOutcomeGatewayProvider;
import com.richard.fyoung.customeradmin.businessoutcome.mapper.BusinessOutcomeMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.tenant.TenantSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessOutcomeServiceTest {

    private BusinessOutcomeMapper mapper;
    private BusinessOutcomeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(BusinessOutcomeMapper.class);
        BusinessOutcomeGatewayProvider provider = mock(BusinessOutcomeGatewayProvider.class);
        when(provider.get()).thenReturn(new BusinessOutcomeGateway(mapper));
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T09:00:00Z"), ZoneOffset.UTC);
        service = new BusinessOutcomeService(provider, clock);
    }

    @Test
    void summary_shouldExposeProxyOutcomeAndNeverFabricateSessionCost() {
        BusinessOutcomeAggregateRow row = aggregate();
        when(mapper.aggregate("tenant-a", "support-agent", 1_000L, 2_000L)).thenReturn(row);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            BusinessOutcomeSummaryVO result = service.summary(1_000L, 2_000L, " support-agent ");

            assertEquals(10, result.totalSessions());
            assertEquals(new BigDecimal("0.800000"), result.successfulSessionRate());
            assertEquals(new BigDecimal("0.600000"), result.autoResolvedProxyRate());
            assertEquals(new BigDecimal("0.200000"), result.handoffRate());
            assertEquals(900L, result.totalTokens());
            assertEquals("PARTIAL", result.tokenAvailability().status());
            assertEquals(new BigDecimal("4.25"), result.averageCsat());
            assertEquals(new BigDecimal("0.750000"), result.csatResponseRate());
            assertEquals(new BigDecimal("0.666667"), result.csatSatisfiedRate());
            assertNull(result.totalCost());
            assertNull(result.costPerAutoResolvedSession());
            assertEquals("UNAVAILABLE", result.costAvailability().status());
            assertTrue(result.costAvailability().reason().contains("未进行比例分摊"));
            assertTrue(result.definitions().autoResolvedProxy().contains("代理指标"));
            verify(mapper).aggregate("tenant-a", "support-agent", 1_000L, 2_000L);
        }
    }

    @Test
    void summary_shouldReturnUnavailableTokenInsteadOfFakeZeroWhenAllUsageMissing() {
        BusinessOutcomeAggregateRow row = aggregate();
        row.setKnownTokenCalls(0L);
        row.setUnknownTokenCalls(12L);
        row.setKnownTotalTokens(null);
        when(mapper.aggregate("tenant-a", null, 1_000L, 2_000L)).thenReturn(row);

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            BusinessOutcomeSummaryVO result = service.summary(1_000L, 2_000L, null);
            assertNull(result.totalTokens());
            assertEquals("UNAVAILABLE", result.tokenAvailability().status());
        }
    }

    @Test
    void sessions_shouldPreservePerSessionEvidenceAndAvailability() {
        BusinessOutcomeSessionRow row = new BusinessOutcomeSessionRow();
        row.setSessionId("session-1");
        row.setAgentCodes("support-agent");
        row.setFirstCallAtMs(1_100L);
        row.setLastCallAtMs(1_900L);
        row.setCallCount(2L);
        row.setFailedCalls(0L);
        row.setKnownTokenCalls(2L);
        row.setUnknownTokenCalls(0L);
        row.setKnownTotalTokens(77L);
        row.setHandedOff(false);
        row.setCsatScore(5);
        when(mapper.countSessions("tenant-a", null, 1_000L, 2_000L)).thenReturn(1L);
        when(mapper.findSessions("tenant-a", null, 1_000L, 2_000L, 0L, 20))
            .thenReturn(List.of(row));

        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            BusinessOutcomeSessionPageVO result = service.sessions(1_000L, 2_000L, null, 1, 20);
            assertEquals(1, result.total());
            assertTrue(result.records().get(0).successful());
            assertTrue(result.records().get(0).autoResolvedProxy());
            assertFalse(result.records().get(0).handedOff());
            assertEquals(77L, result.records().get(0).totalTokens());
            assertEquals("COMPLETE", result.records().get(0).tokenAvailability().status());
            assertEquals(5, result.records().get(0).csatScore());
        }
    }

    @Test
    void query_shouldRejectInvalidWindowAndUnboundedPage() {
        try (MockedStatic<TenantSession> session = mockStatic(TenantSession.class)) {
            session.when(TenantSession::effectiveTenant).thenReturn("tenant-a");
            assertThrows(BizException.class, () -> service.summary(2_000L, 1_000L, null));
            assertThrows(BizException.class,
                () -> service.summary(1L, 91L * 24 * 60 * 60 * 1000, null));
            assertThrows(BizException.class,
                () -> service.sessions(1_000L, 2_000L, null, 1, 201));
        }
    }

    private BusinessOutcomeAggregateRow aggregate() {
        BusinessOutcomeAggregateRow row = new BusinessOutcomeAggregateRow();
        row.setTotalSessions(10L);
        row.setSuccessfulSessions(8L);
        row.setAutoResolvedProxySessions(6L);
        row.setHandoffSessions(2L);
        row.setTotalCalls(12L);
        row.setKnownTokenCalls(9L);
        row.setUnknownTokenCalls(3L);
        row.setKnownTotalTokens(900L);
        row.setCsatInvitedSessions(8L);
        row.setCsatRespondedSessions(6L);
        row.setCsatSatisfiedSessions(4L);
        row.setAverageCsat(new BigDecimal("4.250000"));
        return row;
    }
}
