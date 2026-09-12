package com.richard.fyoung.customerwork.capability.approval;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 标准内存审批也必须按保存时的真实租户分区，不能把同名审批放进全局键空间。 */
class InMemoryApprovalStoreTenantTest {
    private final InMemoryApprovalStore store = new InMemoryApprovalStore();

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void sameIdCanBeSavedAndReadIndependentlyAcrossTenants() {
        var first = request("shared-id");
        var second = request("shared-id");
        TenantContext.runWith("tenant-A", () -> store.save(first));
        TenantContext.runWith("tenant-B", () -> store.save(second));
        TenantContext.set("TENANT-a");
        assertSame(first, store.find("shared-id").orElseThrow());
        assertEquals(List.of(first), store.findAll());
        assertEquals(List.of(first), store.findByStatus(ApprovalStatus.PENDING));
        TenantContext.set("tenant-B");
        assertSame(second, store.find("shared-id").orElseThrow());
    }

    @Test
    void foreignTenantCannotDecideOrExecuteApproval() {
        var request = request("approval-A");
        TenantContext.runWith("tenant-A", () -> store.save(request));
        TenantContext.set("tenant-B");
        assertFalse(store.decide("approval-A", ApprovalStatus.APPROVED, "operator", "note", 2L));
        assertFalse(store.claimExecution("approval-A", 3, 3L, "token"));
        assertFalse(store.completeExecution("approval-A", "token", true, null));
        assertEquals(ApprovalStatus.PENDING, request.getStatus());
    }

    @Test
    void foreignTenantCannotRecoverOrDeleteExecution() {
        var request = request("approval-A");
        request.approve("operator", "note", 2L);
        request.markExecuting(3L, "token");
        TenantContext.runWith("tenant-A", () -> store.save(request));
        TenantContext.set("tenant-B");
        assertEquals(0, store.recoverStuckExecutions(4L));
        store.delete("approval-A");
        assertTrue(TenantContext.callWith("tenant-A", () -> store.find("approval-A")).isPresent());
        assertEquals(ExecutionStatus.EXECUTING, request.getExecutionStatus());
    }

    @Test
    void everyOperationRejectsMissingTenant() {
        var request = request("approval-A");
        TenantContext.clear();
        List<Runnable> operations = List.of(() -> store.save(request), () -> store.update(request),
            () -> store.find("approval-A"), store::findAll, () -> store.findByStatus(ApprovalStatus.PENDING),
            () -> store.decide("approval-A", ApprovalStatus.APPROVED, "operator", null, 1L),
            () -> store.claimExecution("approval-A", 3, 1L, "token"),
            () -> store.completeExecution("approval-A", "token", true, null),
            () -> store.recoverStuckExecutions(2L), () -> store.delete("approval-A"));
        assertAll(operations.stream().map(operation -> () ->
            assertThrows(TenantContextMissingException.class, operation::run)));
    }

    private ApprovalRequest request(String id) {
        return new ApprovalRequest(id, ApprovalType.REFUND, "session", "order", "9.00", "原因", 1L);
    }
}
