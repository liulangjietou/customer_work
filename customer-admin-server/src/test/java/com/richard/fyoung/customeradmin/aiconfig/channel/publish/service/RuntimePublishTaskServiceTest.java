package com.richard.fyoung.customeradmin.aiconfig.channel.publish.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePublishTaskServiceTest {

    private final RuntimePublishTaskMapper taskMapper = mock(RuntimePublishTaskMapper.class);
    private final RuntimeConfigAckMapper ackMapper = mock(RuntimeConfigAckMapper.class);
    private final RuntimePublishProperties properties = new RuntimePublishProperties();
    private final AdminTenantProperties tenantProperties = new AdminTenantProperties();
    private final RuntimePublishTaskService service = new RuntimePublishTaskService(
        taskMapper, ackMapper, properties, tenantProperties);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void enqueueBindsTaskToCurrentTenant() {
        TenantContext.set("tenant-a");
        service.enqueueAgent(42L);

        ArgumentCaptor<RuntimePublishTask> captor = ArgumentCaptor.forClass(RuntimePublishTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals("tenant-a", captor.getValue().getTenantId());
        assertEquals(42L, captor.getValue().getTargetId());
        assertEquals(RuntimePublishStatus.PENDING.name(), captor.getValue().getStatus());
    }

    @Test
    void expiredLeaseCanBeClaimedByOnlyOneWorker() {
        RuntimePublishTask candidate = task("task-1", "tenant-a", "rev-1", "hash-1");
        candidate.setStatus(RuntimePublishStatus.PROCESSING.name());
        when(taskMapper.findDueCandidates(anyLong(), anyInt())).thenReturn(List.of(candidate));
        when(taskMapper.claim(eq("task-1"), anyString(), anyLong(), anyLong())).thenReturn(1);

        List<RuntimePublishTask> claimed = service.claimDue();

        assertEquals(1, claimed.size());
        assertEquals(RuntimePublishStatus.PROCESSING.name(), claimed.get(0).getStatus());
        verify(taskMapper).claim(eq("task-1"), anyString(), anyLong(), anyLong());
    }

    @Test
    void ackAggregateRequiresConfiguredInstanceCount() {
        properties.setMinimumAckCount(2);
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        when(taskMapper.findByRevision("tenant-a", "rev-1")).thenReturn(task);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "APPLIED")).thenReturn(2);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "REJECTED")).thenReturn(0);
        TenantContext.set("tenant-a");

        RuntimePublishStatus status = service.recordAck(
            new RuntimeConfigAck("rev-1", "hash-1", "pod-2", "APPLIED", null, 100L));

        assertEquals(RuntimePublishStatus.APPLIED, status);
        verify(ackMapper).upsert(any());
        verify(taskMapper).updateAckStatus(eq("tenant-a"), eq("rev-1"), eq("APPLIED"), anyLong());
    }

    @Test
    void contentHashMismatchIsRejectedWithoutPersistingAck() {
        when(taskMapper.findByRevision("tenant-a", "rev-1"))
            .thenReturn(task("task-1", "tenant-a", "rev-1", "expected"));
        TenantContext.set("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> service.recordAck(
            new RuntimeConfigAck("rev-1", "tampered", "pod-1", "APPLIED", null, 100L)));

        verify(ackMapper, never()).upsert(any());
    }

    private RuntimePublishTask task(String id, String tenantId, String revision, String hash) {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(id);
        task.setTenantId(tenantId);
        task.setTargetId(42L);
        task.setRevision(revision);
        task.setContentHash(hash);
        task.setPublishScope("FULL");
        task.setAttempts(0);
        task.setCreatedAtMs(100L);
        return task;
    }
}
