package com.richard.fyoung.customeradmin.improvement.service;

import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImprovementAutomationWorkerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void scan_shouldIsolateEachTenantPersistFailureAndContinueNextCase() {
        ImprovementAutomationLeaseService leaseService =
            mock(ImprovementAutomationLeaseService.class);
        ImprovementCaseService caseService = mock(ImprovementCaseService.class);
        AgentImprovementCase failed = row(1L, "tenant-a");
        AgentImprovementCase succeeded = row(2L, "tenant-b");
        when(leaseService.claimDue()).thenReturn(List.of(failed, succeeded));
        doAnswer(invocation -> {
            AgentImprovementCase current = invocation.getArgument(0);
            assertEquals(current.getTenantId(), TenantContext.require());
            if (current.getId().equals(1L)) {
                throw new IllegalStateException("downstream failed");
            }
            return null;
        }).when(caseService).processAutomation(any(AgentImprovementCase.class));
        doAnswer(invocation -> {
            AgentImprovementCase current = invocation.getArgument(0);
            assertEquals("tenant-a", TenantContext.require());
            assertEquals(1L, current.getId());
            return null;
        }).when(caseService).markAutomationFailure(
            any(AgentImprovementCase.class), any(Throwable.class));
        ImprovementAutomationWorker worker = new ImprovementAutomationWorker(
            new ImprovementAutomationProperties(), leaseService, caseService);
        TenantContext.set("outer-tenant");

        worker.scanSafely();

        assertEquals("outer-tenant", TenantContext.require());
        verify(caseService, times(2)).processAutomation(any(AgentImprovementCase.class));
        verify(caseService).markAutomationFailure(
            any(AgentImprovementCase.class), any(IllegalStateException.class));
    }

    @Test
    void scan_shouldContainLeaseFailureWithoutChangingCallerTenant() {
        ImprovementAutomationLeaseService leaseService =
            mock(ImprovementAutomationLeaseService.class);
        when(leaseService.claimDue()).thenThrow(new IllegalStateException("database unavailable"));
        ImprovementAutomationWorker worker = new ImprovementAutomationWorker(
            new ImprovementAutomationProperties(), leaseService, mock(ImprovementCaseService.class));
        TenantContext.set("outer-tenant");

        assertDoesNotThrow(worker::scanSafely);

        assertEquals("outer-tenant", TenantContext.require());
    }

    @Test
    void scan_shouldContainFailureStatePersistenceError() {
        ImprovementAutomationLeaseService leaseService =
            mock(ImprovementAutomationLeaseService.class);
        ImprovementCaseService caseService = mock(ImprovementCaseService.class);
        AgentImprovementCase failed = row(1L, "tenant-a");
        when(leaseService.claimDue()).thenReturn(List.of(failed));
        doAnswer(invocation -> {
            throw new IllegalStateException("processing failed");
        }).when(caseService).processAutomation(failed);
        doAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.require());
            throw new IllegalStateException("state persistence failed");
        }).when(caseService).markAutomationFailure(
            any(AgentImprovementCase.class), any(Throwable.class));

        assertDoesNotThrow(() -> new ImprovementAutomationWorker(
            new ImprovementAutomationProperties(), leaseService, caseService).scanSafely());

        assertNull(TenantContext.get());
    }

    @Test
    void disabledWorker_shouldStartAndDestroyWithoutAllocatingScheduler() {
        ImprovementAutomationProperties properties = new ImprovementAutomationProperties();
        properties.setEnabled(false);
        ImprovementAutomationWorker worker = new ImprovementAutomationWorker(properties,
            mock(ImprovementAutomationLeaseService.class), mock(ImprovementCaseService.class));

        assertDoesNotThrow(worker::start);
        assertDoesNotThrow(worker::destroy);
    }

    private AgentImprovementCase row(Long id, String tenantId) {
        AgentImprovementCase row = new AgentImprovementCase();
        row.setId(id);
        row.setTenantId(tenantId);
        return row;
    }
}
