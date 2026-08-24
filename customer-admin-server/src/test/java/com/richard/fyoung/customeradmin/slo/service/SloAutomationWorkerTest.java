package com.richard.fyoung.customeradmin.slo.service;

import com.richard.fyoung.customeradmin.slo.config.SloAutomationProperties;
import com.richard.fyoung.customeradmin.slo.dto.SloEvaluationVO;
import com.richard.fyoung.customeradmin.slo.entity.SloNotificationTask;
import com.richard.fyoung.customeradmin.slo.entity.SloPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SloAutomationWorkerTest {

    private SloEvaluationLeaseService leaseService;
    private SloEvaluationService evaluationService;
    private SloNotificationService notificationService;
    private SloAutomationWorker worker;

    @BeforeEach
    void setUp() {
        leaseService = mock(SloEvaluationLeaseService.class);
        evaluationService = mock(SloEvaluationService.class);
        notificationService = mock(SloNotificationService.class);
        worker = new SloAutomationWorker(new SloAutomationProperties(), leaseService,
            evaluationService, notificationService);
    }

    @Test
    void evaluateSafely_shouldRunClaimedPolicyInTenantAndCompleteLease() {
        SloPolicy policy = new SloPolicy();
        policy.setId(7L);
        policy.setTenantId("tenant-a");
        SloEvaluationVO result = mock(SloEvaluationVO.class);
        when(leaseService.claimDue()).thenReturn(List.of(policy));
        when(evaluationService.evaluateForTenant(7L, "tenant-a")).thenReturn(result);

        worker.evaluateSafely();

        verify(evaluationService).evaluateForTenant(7L, "tenant-a");
        verify(leaseService).complete(policy, result);
    }

    @Test
    void notifySafely_shouldReturnFailedDeliveryToRetryQueue() {
        SloNotificationTask task = new SloNotificationTask();
        task.setId("task-1");
        task.setTenantId("tenant-a");
        when(notificationService.claimDue()).thenReturn(List.of(task));
        IllegalStateException failure = new IllegalStateException("message unavailable");
        doThrow(failure).when(notificationService).deliver(task);

        worker.notifySafely();

        verify(notificationService).markFailed(task, failure);
    }
}
