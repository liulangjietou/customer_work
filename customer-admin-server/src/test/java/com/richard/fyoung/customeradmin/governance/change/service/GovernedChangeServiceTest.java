package com.richard.fyoung.customeradmin.governance.change.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.governance.change.GovernanceProperties;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeStatus;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeType;
import com.richard.fyoung.customeradmin.governance.change.dto.GovernedChangeVO;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernanceAuditEventMapper;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernedChangeServiceTest {

    private final GovernedChangeRequestMapper requestMapper = mock(GovernedChangeRequestMapper.class);
    private final GovernanceAuditEventMapper auditMapper = mock(GovernanceAuditEventMapper.class);
    private final GovernedChangeStateService stateService = mock(GovernedChangeStateService.class);
    private final GovernedChangeExecutor executor = mock(GovernedChangeExecutor.class);

    @Test
    void checkerApprovalExecutesTypedCommandAndPersistsResult() {
        AiGovernedChangeRequest claimed = request(GovernedChangeStatus.EXECUTING);
        AiGovernedChangeRequest completed = request(GovernedChangeStatus.EXECUTED);
        when(executor.types()).thenReturn(Set.of(GovernedChangeType.CONFIG_ROLLBACK));
        when(stateService.claim("request-1", "tenant-a", 12L, "checker", "verified"))
            .thenReturn(claimed);
        when(executor.execute(claimed)).thenReturn(Map.of("operationId", "operation-1"));
        when(stateService.complete(org.mockito.ArgumentMatchers.eq("request-1"),
            org.mockito.ArgumentMatchers.eq("tenant-a"), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(completed);
        GovernedChangeService service = service();

        GovernedChangeVO result = TenantContext.callWith("tenant-a",
            () -> service.approve("request-1", 12L, "checker", "verified"));

        assertEquals(GovernedChangeStatus.EXECUTED.name(), result.status());
        verify(executor).execute(claimed);
        verify(stateService).complete(org.mockito.ArgumentMatchers.eq("request-1"),
            org.mockito.ArgumentMatchers.eq("tenant-a"), org.mockito.ArgumentMatchers.contains("operation-1"));
    }

    @Test
    void executionFailurePersistsStableFailureState() {
        AiGovernedChangeRequest claimed = request(GovernedChangeStatus.EXECUTING);
        when(executor.types()).thenReturn(Set.of(GovernedChangeType.CONFIG_ROLLBACK));
        when(stateService.claim("request-1", "tenant-a", 12L, "checker", "verified"))
            .thenReturn(claimed);
        when(executor.execute(claimed)).thenThrow(new IllegalStateException("sensitive downstream text"));
        when(stateService.fail("request-1", "tenant-a", "GOVERNED_CHANGE_EXECUTION_FAILED"))
            .thenReturn(request(GovernedChangeStatus.FAILED));
        GovernedChangeService service = service();

        assertThrows(IllegalStateException.class, () -> TenantContext.runWith("tenant-a",
            () -> service.approve("request-1", 12L, "checker", "verified")));

        verify(stateService).fail(
            "request-1", "tenant-a", "GOVERNED_CHANGE_EXECUTION_FAILED");
    }

    private GovernedChangeService service() {
        return new GovernedChangeService(requestMapper, auditMapper, stateService,
            new GovernanceProperties(), new ObjectMapper(), List.of(executor));
    }

    private AiGovernedChangeRequest request(GovernedChangeStatus status) {
        AiGovernedChangeRequest request = new AiGovernedChangeRequest();
        request.setId("request-1");
        request.setTenantId("tenant-a");
        request.setChangeType(GovernedChangeType.CONFIG_ROLLBACK.name());
        request.setStatus(status.name());
        return request;
    }
}
