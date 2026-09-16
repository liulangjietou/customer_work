package com.richard.fyoung.customerwork.capability.approval;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 定时器没有请求身份，应逐一恢复标准内存存储中实际出现过的租户，不能默认落到 default。 */
class InMemoryApprovalSchedulerTenantTest {
    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final PendingApprovalService service = new PendingApprovalService(store);
    private final CustomerWorkProperties properties = new CustomerWorkProperties();

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void scheduledTimeoutsRestoreEachStoredTenant() {
        var first = save("tenant-A");
        var second = save("tenant-B");
        properties.getHumanApproval().setTimeoutSeconds(1);
        properties.getHumanApproval().setTimeoutAction("deny");
        TenantContext.clear();
        assertDoesNotThrow(() -> new ApprovalTimeoutScheduler(properties, service).checkTimeouts());
        assertEquals(ApprovalStatus.DENIED, first.getStatus());
        assertEquals(ApprovalStatus.DENIED, second.getStatus());
        assertNull(TenantContext.get());
    }

    @Test
    void scheduledRetriesRestoreEachStoredTenantAndReleaseContext() {
        var first = save("tenant-A");
        var second = save("tenant-B");
        for (var request : List.of(first, second)) {
            request.approve("operator", null, 2L);
            request.markExecutionFailed("temporary failure");
        }
        var executedTenants = new ArrayList<String>();
        service.onApprove(request -> executedTenants.add(TenantContext.require()));
        TenantContext.clear();
        assertDoesNotThrow(() -> new ApprovalTimeoutScheduler(properties, service).retryExecutionFailures());
        assertEquals(List.of("tenant-A", "tenant-B"), executedTenants.stream().sorted().toList());
        assertEquals(ExecutionStatus.EXECUTED, first.getExecutionStatus());
        assertEquals(ExecutionStatus.EXECUTED, second.getExecutionStatus());
        assertNull(TenantContext.get());
    }

    private ApprovalRequest save(String tenantId) {
        var request = new ApprovalRequest("shared-id", ApprovalType.REFUND, "session", "order", "9.00", "原因", 1L);
        TenantContext.runWith(tenantId, () -> store.save(request));
        return request;
    }
}
