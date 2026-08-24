package com.richard.fyoung.customeradmin.aiconfig.channel.publish;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher.PreparedRuntimeConfig;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateDecision;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalReleaseGateService;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.service.RuntimePublishTaskService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(taskService).publishWithFencing(eq(task), any(Runnable.class));

        worker.dispatchSafely();

        verify(taskService).attachMetadata(task);
        verify(publisher).publishPrepared(task, prepared);
        verify(taskService).publishWithFencing(eq(task), any(Runnable.class));
    }

    @Test
    void publishFailureReturnsTaskToRetryState() {
        RuntimePublishTask task = task();
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", null, "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-1", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);
        org.mockito.Mockito.doThrow(new IllegalStateException("nacos unavailable"))
            .when(publisher).publishPrepared(task, prepared);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(taskService).publishWithFencing(eq(task), any(Runnable.class));

        worker.dispatchSafely();

        verify(taskService).markDeliveryFailed(eq(task), any(IllegalStateException.class));
        verify(taskService).publishWithFencing(eq(task), any(Runnable.class));
    }

    @Test
    void deterministicContentChangeShouldTerminateWithoutDeliveryRetry() {
        RuntimePublishTask task = task();
        task.setContentHash("hash-old");
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", null, "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-new", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);

        worker.dispatchSafely();

        verify(taskService).markContentChangedTerminal(task);
        verify(taskService, never()).markDeliveryFailed(eq(task), any());
        verify(taskService, never()).publishWithFencing(eq(task), any(Runnable.class));
        verify(publisher, never()).publishPrepared(eq(task), any());
    }

    @Test
    void gateBlockedTaskShouldNotPublishOrConsumeDeliveryRetry() {
        EvalReleaseGateService gateService = mock(EvalReleaseGateService.class);
        RuntimePublishWorker gatedWorker = new RuntimePublishWorker(
            new RuntimePublishProperties(), taskService, publisher, gateService);
        RuntimePublishTask task = task();
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", "web", "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-1", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);
        when(gateService.evaluateAndRecord(task, prepared)).thenReturn(
            new EvalGateDecision(EvalGateStatus.BLOCKED, List.of(), 1L));

        gatedWorker.dispatchSafely();

        verify(publisher, never()).publishPrepared(task, prepared);
        verify(taskService, never()).publishWithFencing(eq(task), any(Runnable.class));
        verify(taskService, never()).markDeliveryFailed(eq(task), any());
    }

    @Test
    void healthOverlayTaskShouldBypassEvalGateAndUseReliablePublish() {
        EvalReleaseGateService gateService = mock(EvalReleaseGateService.class);
        RuntimePublishWorker gatedWorker = new RuntimePublishWorker(
            new RuntimePublishProperties(), taskService, publisher, gateService);
        RuntimePublishTask task = task();
        task.setPublishIntent(RuntimePublishIntent.HEALTH_OVERLAY.name());
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", "web", "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-1", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(taskService).publishWithFencing(eq(task), any(Runnable.class));

        gatedWorker.dispatchSafely();

        verify(gateService, never()).evaluateAndRecord(task, prepared);
        verify(publisher).publishPrepared(task, prepared);
        verify(taskService).publishWithFencing(eq(task), any(Runnable.class));
    }

    @Test
    void leaseLostBeforeFencedPublishShouldStopWithoutRetryMutation() {
        RuntimePublishTask task = task();
        PreparedRuntimeConfig prepared = new PreparedRuntimeConfig(
            "agent-a", null, "runtime-config", "DEFAULT_GROUP", "rev-1", "hash-1", "{}");
        when(taskService.claimDue()).thenReturn(List.of(task));
        when(publisher.prepareTask(task)).thenReturn(prepared);
        org.mockito.Mockito.doThrow(new RuntimePublishLeaseLostException(task.getId()))
            .when(taskService).publishWithFencing(eq(task), any(Runnable.class));

        worker.dispatchSafely();

        verify(publisher, never()).publishPrepared(eq(task), any());
        verify(taskService, never()).markDeliveryFailed(eq(task), any());
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
