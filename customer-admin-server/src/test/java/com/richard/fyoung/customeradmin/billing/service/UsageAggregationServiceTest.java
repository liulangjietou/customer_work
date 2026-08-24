package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.config.BillingSettlementProperties;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import com.richard.fyoung.customeradmin.billing.event.UsageAggregationCompletedEvent;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGateway;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGatewayProvider;
import com.richard.fyoung.customeradmin.billing.mapper.CustomerUsageFactMapper;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageAggregationServiceTest {

    private CwTenantUsageDailyMapper usageMapper;
    private CustomerUsageFactMapper sourceMapper;
    private ApplicationEventPublisher eventPublisher;
    private UsageAggregationService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(CwTenantUsageDailyMapper.class);
        sourceMapper = mock(CustomerUsageFactMapper.class);
        CustomerUsageFactGatewayProvider sourceProvider = mock(CustomerUsageFactGatewayProvider.class);
        when(sourceProvider.get()).thenReturn(new CustomerUsageFactGateway(sourceMapper));
        BillingSettlementProperties properties = new BillingSettlementProperties();
        properties.setZoneId("Asia/Shanghai");
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new UsageAggregationService(usageMapper, sourceProvider, properties, eventPublisher);
    }

    @Test
    void aggregate_shouldFreezeSourceBoundary_rebuildAndVerifyExactFacts() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        UsageAggregate first = usage("tenant-a", "model-1", "CNY", "1.23456789012345");
        UsageAggregate second = usage(null, "model-2", "CNY", "0.00000000000001");
        when(usageMapper.lockAggregationDate(date)).thenReturn(date);
        when(usageMapper.findTenantIdsByDate(date)).thenReturn(List.of("tenant-old"));
        when(sourceMapper.maxCallLogId(isNull(), anyLong(), anyLong())).thenReturn(99L);
        when(sourceMapper.aggregate(isNull(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq(99L)))
            .thenReturn(List.of(first, second));
        when(usageMapper.insert(any(CwTenantUsageDaily.class))).thenReturn(1);
        when(usageMapper.listByDate(null, date)).thenReturn(List.of(
            persisted(first, 99L), persisted(second, 99L)));

        assertEquals(2, service.aggregate(date));

        ArgumentCaptor<CwTenantUsageDaily> entityCaptor =
            ArgumentCaptor.forClass(CwTenantUsageDaily.class);
        verify(usageMapper, org.mockito.Mockito.times(2)).insert(entityCaptor.capture());
        assertEquals(new BigDecimal("1.23456789012345"),
            entityCaptor.getAllValues().get(0).getAmount());
        assertEquals(99L, entityCaptor.getAllValues().get(0).getSourceMaxCallLogId());
        verify(usageMapper).deleteByStatDate(date);

        ArgumentCaptor<UsageAggregationCompletedEvent> eventCaptor =
            ArgumentCaptor.forClass(UsageAggregationCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(date, eventCaptor.getValue().statDate());
        assertEquals(Set.of("tenant-a", "default", "tenant-old"),
            eventCaptor.getValue().tenantIds());
    }

    @Test
    void aggregate_shouldDeleteStaleBillAndNotPublishWhenSourceAndPreviousBillAreEmpty() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        when(usageMapper.lockAggregationDate(date)).thenReturn(date);
        when(sourceMapper.maxCallLogId(isNull(), anyLong(), anyLong())).thenReturn(0L);
        when(usageMapper.findTenantIdsByDate(date)).thenReturn(List.of());
        when(usageMapper.listByDate(null, date)).thenReturn(List.of());

        assertEquals(0, service.aggregate(date));

        verify(usageMapper).deleteByStatDate(date);
        verify(sourceMapper, never()).aggregate(any(), anyLong(), anyLong(), anyLong());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aggregate_shouldRollbackSemanticallyWhenWrittenSnapshotCannotReconcile() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        UsageAggregate source = usage("tenant-a", "model-1", "CNY", "1.00000000000000");
        when(usageMapper.lockAggregationDate(date)).thenReturn(date);
        when(usageMapper.findTenantIdsByDate(date)).thenReturn(List.of());
        when(sourceMapper.maxCallLogId(isNull(), anyLong(), anyLong())).thenReturn(7L);
        when(sourceMapper.aggregate(isNull(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.eq(7L)))
            .thenReturn(List.of(source));
        when(usageMapper.insert(any(CwTenantUsageDaily.class))).thenReturn(1);
        when(usageMapper.listByDate(null, date)).thenReturn(List.of());

        assertThrows(IllegalStateException.class, () -> service.aggregate(date));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void aggregate_shouldFailFastWhenDateLockWasNotAcquired() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        when(usageMapper.lockAggregationDate(date)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.aggregate(date));
        verify(sourceMapper, never()).maxCallLogId(any(), anyLong(), anyLong());
    }

    private UsageAggregate usage(String tenantId, String modelName, String currency, String amount) {
        UsageAggregate row = new UsageAggregate();
        row.setTenantId(tenantId);
        row.setProvider("dashscope");
        row.setModelName(modelName);
        row.setCurrency(currency);
        row.setCallCount(1L);
        row.setInputTokens(10L);
        row.setOutputTokens(20L);
        row.setCachedTokens(0L);
        row.setTotalTokens(30L);
        row.setModelSegmentCount(1L);
        row.setSettledSegmentCount(1L);
        row.setUnsettledSegmentCount(0L);
        row.setPricingStatus("COMPLETE");
        row.setAmount(new BigDecimal(amount));
        return row;
    }

    private UsageAggregate persisted(UsageAggregate source, long sourceMaxCallLogId) {
        UsageAggregate row = usage(source.getTenantId(), source.getModelName(),
            source.getCurrency(), source.getAmount().toPlainString());
        row.setSourceMaxCallLogId(sourceMaxCallLogId);
        return row;
    }
}
