package com.richard.fyoung.customeradmin.tenant.access.service;

import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPublishProperties;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPublishStatus;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.access.dto.TenantAccessDeliveryVO;
import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import com.richard.fyoung.customeradmin.tenant.access.mapper.TenantAccessPublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAccessPublishTaskServiceTest {

    private final TenantAccessPublishTaskMapper mapper = mock(TenantAccessPublishTaskMapper.class);
    private final TenantAccessPublishProperties properties = new TenantAccessPublishProperties();
    private final RuntimePublishProperties runtimePublishProperties = new RuntimePublishProperties();
    private final TenantAccessPublishTaskService service =
        new TenantAccessPublishTaskService(mapper, properties, runtimePublishProperties);

    @Test
    void enqueue_shouldSupersedePendingAndUseDedicatedTenantDataId() {
        service.enqueue(new TenantAccessSnapshot("AcMe", "SUSPENDED", 4L, null));

        verify(mapper).supersedePending(eq("AcMe"), anyLong());
        ArgumentCaptor<TenantAccessPublishTask> captor = ArgumentCaptor.forClass(TenantAccessPublishTask.class);
        verify(mapper).insert(captor.capture());
        TenantAccessPublishTask task = captor.getValue();
        assertEquals("customer-work-tenant-access-tenant-AcMe", task.getDataId());
        assertEquals(TenantAccessPublishStatus.PENDING.name(), task.getStatus());
        assertEquals(4L, task.getAccessEpoch());
        assertEquals("PROVISION", task.getOperation());
        assertEquals("NOT_REQUIRED", task.getSessionRevocationStatus());
    }

    @Test
    void claimDue_shouldAcquireTenantLease() {
        TenantAccessPublishTask task = task();
        when(mapper.findDueCandidates(anyLong(), eq(properties.getBatchSize()))).thenReturn(List.of(task));
        when(mapper.claim(eq(task.getId()), eq(task.getTenantId()), org.mockito.ArgumentMatchers.anyString(),
            anyLong(), anyLong())).thenReturn(1);

        List<TenantAccessPublishTask> claimed = service.claimDue();

        assertEquals(List.of(task), claimed);
        assertEquals(TenantAccessPublishStatus.PROCESSING.name(), task.getStatus());
        assertEquals("acme", task.getActiveLeaseKey());
    }

    @Test
    void failedOlderTask_shouldBecomeSupersededWhenNewerSnapshotExists() {
        TenantAccessPublishTask task = task();
        when(mapper.countNewerTasks("acme", 10L)).thenReturn(1);
        when(mapper.markDeliveryFailed(eq("task-1"), org.mockito.ArgumentMatchers.anyString(),
            eq(TenantAccessPublishStatus.SUPERSEDED.name()), eq(1), anyLong(),
            eq("nacos unavailable"), anyLong())).thenReturn(1);

        service.markDeliveryFailed(task, new IllegalStateException("nacos unavailable"));

        verify(mapper).markDeliveryFailed(eq("task-1"), org.mockito.ArgumentMatchers.anyString(),
            eq(TenantAccessPublishStatus.SUPERSEDED.name()), eq(1), anyLong(),
            eq("nacos unavailable"), anyLong());
        assertTrue(properties.getMaxAttempts() == 0, "默认必须持续重试而非永久放弃安全撤权");
    }

    @Test
    void latestPublishedOffboardingTask_shouldExposeVerifiableCompletion() {
        TenantAccessPublishTask task = task();
        task.setOperation("OFFBOARD");
        task.setSessionRevocationStatus("EPOCH_ENFORCED");
        task.setChannelDisableStatus("COMPLETED");
        task.setChannelsDisabledCount(4);
        task.setStatus(TenantAccessPublishStatus.PUBLISHED.name());
        when(mapper.findLatest("acme")).thenReturn(task);

        TenantAccessDeliveryVO delivery = service.latest("acme");

        assertEquals("COMPLETED", delivery.getOrchestrationStatus());
        assertEquals("OFFBOARD", delivery.getOperation());
        assertEquals("EPOCH_ENFORCED", delivery.getSessionRevocationStatus());
        assertEquals("COMPLETED", delivery.getChannelDisableStatus());
        assertEquals(4, delivery.getChannelsDisabledCount());
    }

    private TenantAccessPublishTask task() {
        TenantAccessPublishTask task = new TenantAccessPublishTask();
        task.setId("task-1");
        task.setSeq(10L);
        task.setTenantId("acme");
        task.setTenantStatus("SUSPENDED");
        task.setAccessEpoch(4L);
        task.setAttempts(0);
        return task;
    }
}
