package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.PreparedRuntimeConfig;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimePublishWorkerTest {

    private final RuntimePublishTaskService taskService = mock(RuntimePublishTaskService.class);
    private final CustomerWorkConfigPublisher publisher = mock(CustomerWorkConfigPublisher.class);
    private final RuntimePublishWorker worker = new RuntimePublishWorker(
        new RuntimePublishProperties(), taskService, publisher);

    @Test
    void successfulTaskPersistsMetadataBeforePublish() {
        RuntimePublishTask task = task();
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", "web", "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-1", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);

        worker.dispatchSafely();

        verify(taskService).attachMetadata(task);
        verify(publisher).publishPrepared(task, prepared);
        verify(taskService).markPublished(task);
    }

    @Test
    void publishFailureReturnsTaskToRetryState() {
        RuntimePublishTask task = task();
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenThrow(new IllegalStateException("nacos unavailable"));

        worker.dispatchSafely();

        verify(taskService).markDeliveryFailed(eq(task), any(IllegalStateException.class));
        verify(taskService, never()).markPublished(task);
    }

    private RuntimePublishTask task() {
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId("task-1");
        task.setTenantId("tenant-a");
        task.setTargetId(42L);
        task.setPublishScope("FULL");
        task.setAttempts(0);
        task.setCreatedAtMs(100L);
        return task;
    }
}
