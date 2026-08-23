package com.richard.fyoung.customeradmin.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.richard.fyoung.customeradmin.billing.dto.CostAlertVO;
import com.richard.fyoung.customeradmin.billing.entity.CostAlert;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertStatus;
import com.richard.fyoung.customeradmin.billing.entity.CostAlertType;
import com.richard.fyoung.customeradmin.billing.mapper.CostAlertMapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CostAlertServiceTest {

    private CostAlertMapper alertMapper;
    private SiteMessageService messageService;
    private CostAlertService service;

    @BeforeEach
    void setUp() {
        alertMapper = mock(CostAlertMapper.class);
        messageService = mock(SiteMessageService.class);
        service = new CostAlertService(alertMapper, messageService);
        TenantContext.clear();
    }

    @Test
    void createIfAbsent_shouldDeduplicateAlertAndMessageByDatabaseUniqueKey() {
        CostAlert alert = alert();
        when(alertMapper.insertIgnore(alert)).thenReturn(0);

        assertFalse(service.createIfAbsent(alert));

        verify(alertMapper, never()).findBillingViewUserIds(any());
        verifyNoInteractions(messageService);
    }

    @Test
    void createIfAbsent_shouldSendOnlyToTenantBillingViewersInsideTenantContext() {
        CostAlert alert = alert();
        alert.setId(9L);
        when(alertMapper.insertIgnore(alert)).thenReturn(1);
        when(alertMapper.findBillingViewUserIds("tenant-a")).thenReturn(List.of(7L, 8L));
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return null;
        }).when(messageService).send(any(), any(), any(), any(), any(), any());

        assertTrue(service.createIfAbsent(alert));

        verify(messageService).send(eq(7L), any(), any(), eq("BILLING_COST_ALERT"), eq("9"), eq("/system/billing"));
        verify(messageService).send(eq(8L), any(), any(), eq("BILLING_COST_ALERT"), eq("9"), eq("/system/billing"));
        assertEquals(null, TenantContext.get());
    }

    @Test
    void list_shouldKeepExplicitTenantPredicateWhileTenantInterceptorIsBypassed() {
        CostAlert alert = alert();
        when(alertMapper.selectList(any())).thenAnswer(invocation -> {
            assertTrue(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"));
            return List.of(alert);
        });

        List<CostAlertVO> result = service.list("tenant-a", "open", 500);

        assertEquals(1, result.size());
        ArgumentCaptor<QueryWrapper<CostAlert>> captor = wrapperCaptor();
        verify(alertMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("tenant-a"));
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue("OPEN"));
        assertTrue(sqlSegment.contains("LIMIT 200"));
        assertFalse(InterceptorIgnoreHelper.willIgnoreTenantLine("anyMapperId"));
    }

    @Test
    void acknowledge_shouldHideOtherTenantAlertAsNotFound() {
        when(alertMapper.selectOne(any())).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
            () -> service.acknowledge(9L, "tenant-a", 7L));

        assertEquals(ResultCode.RESOURCE_NOT_FOUND, exception.getResultCode());
        verify(alertMapper, never()).acknowledge(any(), any(), any(), any());
    }

    @Test
    void acknowledge_shouldBeIdempotentWhenAlreadyAcknowledged() {
        CostAlert alert = alert();
        alert.setStatus(CostAlertStatus.ACKED.name());
        when(alertMapper.selectOne(any())).thenReturn(alert);

        service.acknowledge(9L, "tenant-a", 7L);

        verify(alertMapper, never()).acknowledge(any(), any(), any(), any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<QueryWrapper<CostAlert>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(QueryWrapper.class);
    }

    private CostAlert alert() {
        CostAlert alert = new CostAlert();
        alert.setId(9L);
        alert.setTenantId("tenant-a");
        alert.setPeriod("MONTHLY");
        alert.setPeriodKey("2026-08");
        alert.setAlertType(CostAlertType.BUDGET_WARNING.name());
        alert.setUsedAmount(new BigDecimal("80"));
        alert.setLimitAmount(new BigDecimal("100"));
        alert.setForecastAmount(new BigDecimal("120"));
        alert.setStatus(CostAlertStatus.OPEN.name());
        alert.setFirstSeenAt(LocalDateTime.of(2026, 8, 10, 0, 0));
        return alert;
    }
}
