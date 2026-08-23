package com.richard.fyoung.customeradmin.aiconfig.channel.publish.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishIntent;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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
        assertEquals("customer-work-runtime-config", captor.getValue().getDataId());
        assertEquals("DEFAULT_GROUP", captor.getValue().getGroupName());
        assertEquals(captor.getValue().getId(), captor.getValue().getOperationId());
        assertEquals(RuntimePublishIntent.NORMAL.name(), captor.getValue().getPublishIntent());
        assertEquals(RuntimePublishStatus.PENDING.name(), captor.getValue().getStatus());
        assertEquals(EvalGateStatus.PENDING.name(), captor.getValue().getGateStatus());
    }

    @Test
    void enqueueExperiment_shouldPersistImmutableExperimentIntent() {
        TenantContext.set("tenant-a");

        String taskId = service.enqueueExperiment(
            42L, 77L, ModelExperimentPublishAction.DEACTIVATE);

        ArgumentCaptor<RuntimePublishTask> captor = ArgumentCaptor.forClass(RuntimePublishTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals(taskId, captor.getValue().getId());
        assertEquals(77L, captor.getValue().getExperimentId());
        assertEquals(ModelExperimentPublishAction.DEACTIVATE.name(),
            captor.getValue().getExperimentPublishAction());
        assertEquals(EvalGateStatus.NOT_REQUIRED.name(), captor.getValue().getGateStatus());
    }

    @Test
    void enqueueSafe_shouldPersistOneAtomicOperationWithWhitelistPatchPerTenant() {
        TenantContext.set("source-tenant");
        AtomicInteger inserts = new AtomicInteger();
        when(taskMapper.insert(any(RuntimePublishTask.class))).thenAnswer(invocation -> {
            RuntimePublishTask task = invocation.getArgument(0);
            assertEquals(task.getTenantId(), TenantContext.get());
            inserts.incrementAndGet();
            return 1;
        });
        RuntimePublishTaskService.SafePublishCommand command =
            new RuntimePublishTaskService.SafePublishCommand(
                "operation-1", RuntimePublishIntent.SAFE_GRAY, 91L, "a".repeat(64),
                "{\"systemPrompt\":\"old\",\"maxIters\":7}", 3, "canary",
                "[\"tenant-a\",\"tenant-b\"]", List.of(
                    new RuntimePublishTaskService.SafePublishTarget("tenant-a", 11L),
                    new RuntimePublishTaskService.SafePublishTarget("tenant-b", 22L)));

        List<RuntimePublishTask> tasks = service.enqueueSafe(command);

        assertEquals(2, inserts.get());
        assertEquals("source-tenant", TenantContext.get(), "批量跨租户入队后必须恢复调用方上下文");
        assertEquals(List.of("tenant-a", "tenant-b"),
            tasks.stream().map(RuntimePublishTask::getTenantId).toList());
        for (RuntimePublishTask task : tasks) {
            assertEquals("operation-1", task.getOperationId());
            assertEquals(RuntimePublishIntent.SAFE_GRAY.name(), task.getPublishIntent());
            assertEquals(91L, task.getSourceConfigVersionId());
            assertEquals("a".repeat(64), task.getSourceContentHash());
            assertEquals(command.rollbackPatchJson(), task.getRollbackPatchJson());
            assertEquals("GRAY", task.getPublishScope());
            assertEquals("DEFAULT_GROUP", task.getGroupName());
            assertEquals(EvalGateStatus.PENDING.name(), task.getGateStatus());
        }
    }

    @Test
    void enqueueSafe_shouldRejectNormalIntentBeforeInsert() {
        RuntimePublishTaskService.SafePublishCommand command =
            new RuntimePublishTaskService.SafePublishCommand(
                "operation-1", RuntimePublishIntent.NORMAL, 91L, "a".repeat(64),
                "{\"systemPrompt\":null,\"maxIters\":null}", 3, null, null,
                List.of(new RuntimePublishTaskService.SafePublishTarget("tenant-a", 11L)));

        assertThrows(IllegalArgumentException.class, () -> service.enqueueSafe(command));

        verify(taskMapper, never()).insert(any(RuntimePublishTask.class));
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
    void enqueueInTenantMode_shouldFreezeTenantScopedDataIdBeforeClaim() {
        tenantProperties.setEnabled(true);
        properties.getNacos().setGroup("FROZEN_GROUP");
        TenantContext.set("tenant-a");

        service.enqueueAgent(42L);

        ArgumentCaptor<RuntimePublishTask> captor = ArgumentCaptor.forClass(RuntimePublishTask.class);
        verify(taskMapper).insert(captor.capture());
        assertEquals("customer-work-runtime-config-tenant-tenant-a", captor.getValue().getDataId());
        assertEquals("FROZEN_GROUP", captor.getValue().getGroupName());
    }

    @Test
    void publishWithFencing_shouldExternalWriteOnlyWhileLeaseRowIsLocked() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        AtomicBoolean published = new AtomicBoolean();
        when(taskMapper.lockLeaseForPublish(eq("task-1"), anyString(), anyLong())).thenReturn(task);
        when(taskMapper.markPublished(eq("task-1"), anyString(), anyLong())).thenReturn(1);

        service.publishWithFencing(task, () -> published.set(true));

        assertTrue(published.get());
        verify(taskMapper).markPublished(eq("task-1"), anyString(), anyLong());
    }

    @Test
    void publishWithFencing_shouldRejectLostLeaseBeforeExternalWrite() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        AtomicBoolean published = new AtomicBoolean();
        when(taskMapper.lockLeaseForPublish(eq("task-1"), anyString(), anyLong())).thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> service.publishWithFencing(task, () -> published.set(true)));

        assertFalse(published.get());
        verify(taskMapper, never()).markPublished(anyString(), anyString(), anyLong());
    }

    @Test
    void publishWithFencing_shouldNotCommitPublishedStateWhenExternalWriteFails() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        when(taskMapper.lockLeaseForPublish(eq("task-1"), anyString(), anyLong())).thenReturn(task);

        assertThrows(IllegalStateException.class,
            () -> service.publishWithFencing(task, () -> {
                throw new IllegalStateException("nacos unavailable");
            }));

        verify(taskMapper, never()).markPublished(anyString(), anyString(), anyLong());
    }

    @Test
    void contentChangedShouldReachTerminalStateWithoutDeliveryBackoff() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        when(taskMapper.markContentChangedTerminal(
            eq("task-1"), anyString(), anyString(), anyLong())).thenReturn(1);

        service.markContentChangedTerminal(task);

        verify(taskMapper).markContentChangedTerminal(
            eq("task-1"), anyString(), eq("runtime config changed while an older publish task was pending"),
            anyLong());
        verify(taskMapper, never()).markDeliveryFailed(any(), any(), any(), anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void ackAggregateRequiresConfiguredInstanceCount() {
        properties.setMinimumAckCount(2);
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        when(taskMapper.lockByRevisionForAck("tenant-a", "rev-1")).thenReturn(task);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "APPLIED")).thenReturn(2);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "REJECTED")).thenReturn(0);
        TenantContext.set("tenant-a");

        RuntimePublishStatus status = service.recordAck(
            new RuntimeConfigAck("rev-1", "hash-1", "pod-2", "APPLIED", null, 100L), "pod-2");

        assertEquals(RuntimePublishStatus.APPLIED, status);
        InOrder ackOrder = inOrder(taskMapper, ackMapper);
        ackOrder.verify(taskMapper).lockByRevisionForAck("tenant-a", "rev-1");
        ackOrder.verify(ackMapper).upsert(any());
        verify(taskMapper).updateAckStatus(eq("tenant-a"), eq("rev-1"), eq("APPLIED"), anyLong());
    }

    @Test
    void rejectedAckAfterApplied_shouldNotReportARegressedAggregate() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        task.setStatus(RuntimePublishStatus.APPLIED.name());
        when(taskMapper.lockByRevisionForAck("tenant-a", "rev-1")).thenReturn(task);
        when(taskMapper.findByRevision("tenant-a", "rev-1")).thenReturn(task);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "APPLIED")).thenReturn(2);
        when(ackMapper.countByStatus("tenant-a", "rev-1", "REJECTED")).thenReturn(1);
        when(taskMapper.updateAckStatus(eq("tenant-a"), eq("rev-1"), eq("PARTIAL"), anyLong()))
            .thenReturn(0);
        TenantContext.set("tenant-a");

        RuntimePublishStatus status = service.recordAck(
            new RuntimeConfigAck("rev-1", "hash-1", "pod-3", "REJECTED", "late", 200L), "pod-3");

        assertEquals(RuntimePublishStatus.APPLIED, status);
        verify(taskMapper).lockByRevisionForAck("tenant-a", "rev-1");
        verify(taskMapper).findByRevision("tenant-a", "rev-1");
    }

    @Test
    void contentHashMismatchIsRejectedWithoutPersistingAck() {
        when(taskMapper.lockByRevisionForAck("tenant-a", "rev-1"))
            .thenReturn(task("task-1", "tenant-a", "rev-1", "expected"));
        TenantContext.set("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> service.recordAck(
            new RuntimeConfigAck("rev-1", "tampered", "pod-1", "APPLIED", null, 100L), "pod-1"));

        verify(ackMapper, never()).upsert(any());
    }

    @Test
    void ackWithoutPositiveAppliedTimestampIsRejected() {
        TenantContext.set("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> service.recordAck(
            new RuntimeConfigAck("rev-1", "hash-1", "pod-1", "APPLIED", null, 0L), "pod-1"));

        verify(taskMapper, never()).lockByRevisionForAck(anyString(), anyString());
        verify(ackMapper, never()).upsert(any());
    }

    @Test
    void selfReportedInstanceIdMustMatchAuthenticatedInstance() {
        TenantContext.set("tenant-a");

        assertThrows(IllegalArgumentException.class, () -> service.recordAck(
            new RuntimeConfigAck("rev-1", "hash-1", "forged-pod", "APPLIED", null, 100L),
            "authenticated-pod"));

        verify(taskMapper, never()).lockByRevisionForAck(anyString(), anyString());
        verify(ackMapper, never()).upsert(any());
    }

    @Test
    void deterministicGateFailureShouldBlockWithoutIncrementingDeliveryAttempts() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        task.setStatus(RuntimePublishStatus.PROCESSING.name());
        when(taskMapper.recordGateDecision(eq("task-1"), anyString(), eq("{}"), eq("[\"run-1\"]"),
            eq("{\"status\":\"BLOCKED\"}"), eq(EvalGateStatus.BLOCKED.name()),
            eq(RuntimePublishStatus.BLOCKED.name()), eq("metric below threshold"), anyLong()))
            .thenReturn(1);

        service.recordGateDecision(task, "{}", "[\"run-1\"]", "{\"status\":\"BLOCKED\"}",
            EvalGateStatus.BLOCKED, "metric below threshold");

        assertEquals(RuntimePublishStatus.BLOCKED.name(), task.getStatus());
        assertEquals(EvalGateStatus.BLOCKED.name(), task.getGateStatus());
        assertEquals(0, task.getAttempts());
        verify(taskMapper, never()).markDeliveryFailed(any(), any(), any(), anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void passedGateShouldKeepLeaseAndClearPreviousError() {
        RuntimePublishTask task = task("task-1", "tenant-a", "rev-1", "hash-1");
        task.setStatus(RuntimePublishStatus.PROCESSING.name());
        when(taskMapper.recordGateDecision(eq("task-1"), anyString(), eq("{}"), eq("[]"),
            eq("{\"status\":\"PASSED\"}"), eq(EvalGateStatus.PASSED.name()),
            eq(RuntimePublishStatus.PROCESSING.name()), isNull(), anyLong())).thenReturn(1);

        service.recordGateDecision(task, "{}", "[]", "{\"status\":\"PASSED\"}",
            EvalGateStatus.PASSED, "must not become last_error");

        assertEquals(RuntimePublishStatus.PROCESSING.name(), task.getStatus());
        assertEquals(EvalGateStatus.PASSED.name(), task.getGateStatus());
    }

    @Test
    void retryShouldFailWhenTaskIsNotGateBlockedOrHasBeenSuperseded() {
        when(taskMapper.retryGateBlocked(eq("task-1"), eq("tenant-a"), anyLong())).thenReturn(0);

        assertThrows(IllegalStateException.class,
            () -> service.retryGateBlocked("task-1", "tenant-a"));
    }

    @Test
    void overrideShouldFailWhenBlockedTaskHasBeenSuperseded() {
        when(taskMapper.overrideGateBlocked(eq("task-1"), eq("tenant-a"), eq(77L), anyLong()))
            .thenReturn(0);

        assertThrows(IllegalStateException.class,
            () -> service.overrideGateBlocked("task-1", "tenant-a", 77L));
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
