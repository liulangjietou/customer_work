package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.entity.CwTenantUsageDaily;
import com.richard.fyoung.customeradmin.billing.event.UsageAggregationCompletedEvent;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageAggregationServiceTest {

    private CwTenantUsageDailyMapper usageMapper;
    private ModelPriceService priceService;
    private ApplicationEventPublisher eventPublisher;
    private UsageAggregationService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(CwTenantUsageDailyMapper.class);
        priceService = mock(ModelPriceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new UsageAggregationService(usageMapper, priceService, eventPublisher);
    }

    @Test
    void aggregate_shouldPublishDeduplicatedTenantEventAfterAllRowsAreWritten() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        when(usageMapper.aggregateFromCallLog(date)).thenReturn(List.of(
            usage("tenant-a", "model-1"),
            usage("tenant-a", "model-2"),
            usage(null, "model-3")));
        when(usageMapper.selectOne(any())).thenReturn(null);
        when(usageMapper.insert(any(CwTenantUsageDaily.class))).thenReturn(1);
        when(priceService.calculate(anyString(), anyString(), anyLong(), anyLong(), anyLong(), any()))
            .thenReturn(new BigDecimal("1.0000"));

        assertEquals(3, service.aggregate(date));

        ArgumentCaptor<UsageAggregationCompletedEvent> eventCaptor =
            ArgumentCaptor.forClass(UsageAggregationCompletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(date, eventCaptor.getValue().statDate());
        assertEquals(Set.of("tenant-a", "default"), eventCaptor.getValue().tenantIds());
        InOrder inOrder = inOrder(usageMapper, eventPublisher);
        inOrder.verify(usageMapper, org.mockito.Mockito.times(3)).insert(any(CwTenantUsageDaily.class));
        inOrder.verify(eventPublisher).publishEvent(any(UsageAggregationCompletedEvent.class));
    }

    @Test
    void aggregate_shouldNotPublishBudgetEventWhenNoUsageExists() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        when(usageMapper.aggregateFromCallLog(date)).thenReturn(List.of());

        assertEquals(0, service.aggregate(date));

        verify(eventPublisher, never()).publishEvent(any());
    }

    private UsageAggregate usage(String tenantId, String modelName) {
        UsageAggregate row = new UsageAggregate();
        row.setTenantId(tenantId);
        row.setProvider("");
        row.setModelName(modelName);
        row.setCallCount(1L);
        row.setInputTokens(10L);
        row.setOutputTokens(20L);
        row.setCachedTokens(0L);
        row.setTotalTokens(30L);
        return row;
    }
}
