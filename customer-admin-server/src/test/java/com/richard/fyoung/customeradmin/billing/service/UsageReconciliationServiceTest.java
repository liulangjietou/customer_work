package com.richard.fyoung.customeradmin.billing.service;

import com.richard.fyoung.customeradmin.billing.config.BillingSettlementProperties;
import com.richard.fyoung.customeradmin.billing.dto.UsageAggregate;
import com.richard.fyoung.customeradmin.billing.dto.UsageReconciliationVO;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGateway;
import com.richard.fyoung.customeradmin.billing.gateway.CustomerUsageFactGatewayProvider;
import com.richard.fyoung.customeradmin.billing.mapper.CustomerUsageFactMapper;
import com.richard.fyoung.customeradmin.billing.mapper.CwTenantUsageDailyMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageReconciliationServiceTest {

    private CwTenantUsageDailyMapper usageMapper;
    private CustomerUsageFactMapper sourceMapper;
    private UsageReconciliationService service;

    @BeforeEach
    void setUp() {
        usageMapper = mock(CwTenantUsageDailyMapper.class);
        sourceMapper = mock(CustomerUsageFactMapper.class);
        CustomerUsageFactGatewayProvider provider = mock(CustomerUsageFactGatewayProvider.class);
        when(provider.get()).thenReturn(new CustomerUsageFactGateway(sourceMapper));
        BillingSettlementProperties properties = new BillingSettlementProperties();
        properties.setMaxReconciliationDays(31);
        service = new UsageReconciliationService(usageMapper, provider, properties);
    }

    @Test
    void reconcile_shouldReturnMatchedForSameFrozenAmountAndCompleteness() {
        LocalDate date = LocalDate.of(2026, 8, 10);
        UsageAggregate source = usage("1.23456789012345", 2L, 2L, 0L, null);
        UsageAggregate bill = usage("1.23456789012345", 2L, 2L, 0L, 88L);
        when(sourceMapper.maxCallLogId(eq("tenant-a"), anyLong(), anyLong())).thenReturn(88L);
        when(sourceMapper.aggregate(eq("tenant-a"), anyLong(), anyLong(), eq(88L)))
            .thenReturn(List.of(source));
        when(usageMapper.listByDate("tenant-a", date)).thenReturn(List.of(bill));

        UsageReconciliationVO result = service.reconcile("tenant-a", date, date).get(0);

        assertEquals("MATCHED", result.status());
        assertEquals(BigDecimal.ZERO.setScale(14), result.difference());
        assertEquals(88L, result.billSourceMaxCallLogId());
    }

    @Test
    void reconcile_shouldDistinguishLateDataAndIncompleteSettlement() {
        LocalDate staleDate = LocalDate.of(2026, 8, 10);
        UsageAggregate source = usage("2.00000000000000", 2L, 2L, 0L, null);
        UsageAggregate staleBill = usage("1.00000000000000", 1L, 1L, 0L, 70L);
        when(sourceMapper.maxCallLogId(eq("tenant-a"), anyLong(), anyLong())).thenReturn(88L);
        when(sourceMapper.aggregate(eq("tenant-a"), anyLong(), anyLong(), eq(88L)))
            .thenReturn(List.of(source));
        when(usageMapper.listByDate("tenant-a", staleDate)).thenReturn(List.of(staleBill));
        assertEquals("STALE", service.reconcile("tenant-a", staleDate, staleDate).get(0).status());

        LocalDate incompleteDate = staleDate.plusDays(1);
        UsageAggregate incompleteSource = usage("1.00000000000000", 2L, 1L, 1L, null);
        UsageAggregate incompleteBill = usage("1.00000000000000", 2L, 1L, 1L, 88L);
        when(sourceMapper.aggregate(eq("tenant-a"), anyLong(), anyLong(), eq(88L)))
            .thenReturn(List.of(incompleteSource));
        when(usageMapper.listByDate("tenant-a", incompleteDate)).thenReturn(List.of(incompleteBill));
        assertEquals("INCOMPLETE",
            service.reconcile("tenant-a", incompleteDate, incompleteDate).get(0).status());
    }

    @Test
    void reconcile_shouldRejectUnboundedRange() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        assertThrows(BizException.class,
            () -> service.reconcile("tenant-a", from, from.plusDays(31)));
        assertThrows(BizException.class,
            () -> service.reconcile("tenant-a", from.plusDays(1), from));
    }

    private UsageAggregate usage(String amount, long modelSegments, long settled,
                                 long unsettled, Long sourceMaxId) {
        UsageAggregate row = new UsageAggregate();
        row.setTenantId("tenant-a");
        row.setProvider("dashscope");
        row.setModelName("qwen-max");
        row.setCurrency("CNY");
        row.setModelSegmentCount(modelSegments);
        row.setSettledSegmentCount(settled);
        row.setUnsettledSegmentCount(unsettled);
        row.setAmount(new BigDecimal(amount));
        row.setSourceMaxCallLogId(sourceMaxId);
        return row;
    }
}
