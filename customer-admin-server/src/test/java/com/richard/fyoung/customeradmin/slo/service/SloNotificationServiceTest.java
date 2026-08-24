package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.message.service.SiteMessageService;
import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.entity.SloAlert;
import com.richard.fyoung.customeradmin.slo.entity.SloAlertEvent;
import com.richard.fyoung.customeradmin.slo.entity.SloNotificationTask;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import com.richard.fyoung.customeradmin.slo.mapper.SloNotificationTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloNotificationServiceTest {

    private static final long NOW = Instant.parse("2026-08-24T08:30:00Z").toEpochMilli();

    private SloNotificationTaskMapper mapper;
    private SiteMessageService siteMessageService;
    private SloNotificationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SloNotificationTaskMapper.class);
        siteMessageService = mock(SiteMessageService.class);
        SloAutomationProperties properties = new SloAutomationProperties();
        properties.setNotificationLeaseMs(30000L);
        properties.setNotificationBatchSize(20);
        properties.setNotificationBaseBackoffMs(1000L);
        properties.setNotificationMaxBackoffMs(60000L);
        service = new SloNotificationService(mapper, siteMessageService, properties,
            Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), "worker-a");
    }

    @Test
    void enqueue_shouldFreezeEventMessageInPersistentTask() {
        SloAlertEvent event = event();
        SloAlert alert = alert();
        SloPolicy policy = policy();

        service.enqueue(event, alert, policy);

        ArgumentCaptor<SloNotificationTask> captor = ArgumentCaptor.forClass(SloNotificationTask.class);
        verify(mapper).insertIgnore(captor.capture());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertEquals(201L, captor.getValue().getEventId());
        assertTrue(captor.getValue().getContent().contains("客服 SLO"));
    }

    @Test
    void claimAndDeliver_shouldUseLeaseAndCommitAllRecipientMessages() {
        SloNotificationTask task = task();
        when(mapper.findDueCandidates(NOW, 20)).thenReturn(List.of(task));
        when(mapper.claim(task.getId(), "worker-a", NOW, NOW + 30000L)).thenReturn(1);

        List<SloNotificationTask> claimed = service.claimDue();

        assertEquals(1, claimed.size());
        when(mapper.lockOwned(task.getId(), "worker-a", NOW)).thenReturn(task);
        when(mapper.findSloViewUserIds("tenant-a")).thenReturn(List.of(9L, 10L));
        when(mapper.markDelivered(task.getId(), "worker-a", 2, NOW)).thenReturn(1);

        service.deliver(task);

        verify(siteMessageService, times(2)).send(anyLong(), eq(task.getTitle()), eq(task.getContent()),
            eq("SLO_ALERT_EVENT"), eq("201"), eq("/system/slo"));
        verify(mapper).markDelivered(task.getId(), "worker-a", 2, NOW);
    }

    @Test
    void markFailed_shouldPersistRetryInsteadOfDroppingNotification() {
        SloNotificationTask task = task();
        task.setAttempts(1);

        service.markFailed(task, new IllegalStateException("site message unavailable"));

        verify(mapper).markFailed(eq(task.getId()), eq("worker-a"), eq(2),
            anyLong(), eq("site message unavailable"), eq(NOW));
    }

    private SloNotificationTask task() {
        SloNotificationTask task = new SloNotificationTask();
        task.setId("task-1");
        task.setTenantId("tenant-a");
        task.setEventId(201L);
        task.setTitle("SLO 错误预算告警");
        task.setContent("content");
        task.setStatus("PENDING");
        task.setAttempts(0);
        return task;
    }

    private SloAlertEvent event() {
        SloAlertEvent event = new SloAlertEvent();
        event.setId(201L);
        event.setTenantId("tenant-a");
        event.setAlertId(101L);
        event.setPolicyId(7L);
        event.setEventType("OPENED");
        event.setShortBurnRate(new BigDecimal("4.000000"));
        event.setLongBurnRate(new BigDecimal("3.000000"));
        return event;
    }

    private SloAlert alert() {
        SloAlert alert = new SloAlert();
        alert.setId(101L);
        return alert;
    }

    private SloPolicy policy() {
        SloPolicy policy = new SloPolicy();
        policy.setId(7L);
        policy.setPolicyName("客服 SLO");
        policy.setScopeType("AGENT");
        policy.setScopeKey("support-agent");
        return policy;
    }
}
