package com.richard.fyoung.customeradmin.tenant.access;

import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import com.richard.fyoung.customeradmin.tenant.access.service.TenantAccessPublishTaskService;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAccessPublishWorkerTest {

    private final TenantAccessPublishTaskService taskService = mock(TenantAccessPublishTaskService.class);
    private final TenantAccessPublisher publisher = mock(TenantAccessPublisher.class);
    private final TenantAccessPublishWorker worker = new TenantAccessPublishWorker(
        new TenantAccessPublishProperties(), new RuntimePublishProperties(), taskService, publisher);

    @Test
    void successfulTask_shouldPublishAndPersistCompletion() {
        TenantAccessPublishTask task = task();
        when(taskService.claimDue()).thenReturn(List.of(task));

        worker.dispatchSafely();

        verify(publisher).publish(task);
        verify(taskService).markPublished(task);
    }

    @Test
    void failedTask_shouldReturnToReliableStateMachine() {
        TenantAccessPublishTask task = task();
        when(taskService.claimDue()).thenReturn(List.of(task));
        doThrow(new IllegalStateException("nacos unavailable")).when(publisher).publish(task);

        worker.dispatchSafely();

        verify(taskService).markDeliveryFailed(eq(task), any(IllegalStateException.class));
        verify(taskService, never()).markPublished(task);
    }

    private TenantAccessPublishTask task() {
        TenantAccessPublishTask task = new TenantAccessPublishTask();
        task.setId("task-1");
        task.setTenantId("acme");
        task.setAttempts(0);
        return task;
    }
}
