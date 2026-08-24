package com.richard.fyoung.customeradmin.governance.change.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.governance.change.GovernedChangeStatus;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernedChangeRequestMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernedChangeStateServiceTest {

    private final GovernedChangeRequestMapper mapper = mock(GovernedChangeRequestMapper.class);
    private final GovernanceAuditWriter auditWriter = mock(GovernanceAuditWriter.class);
    private final GovernedChangeStateService service =
        new GovernedChangeStateService(mapper, auditWriter);

    @Test
    void makerCannotApproveOwnChange() {
        AiGovernedChangeRequest request = request(11L, GovernedChangeStatus.PENDING);
        when(mapper.claimForExecution(anyString(), anyString(), any(), any(), any(), any()))
            .thenReturn(0);
        when(mapper.selectById("request-1")).thenReturn(request);

        BizException exception = assertThrows(BizException.class,
            () -> service.claim("request-1", "tenant-a", 11L, "maker", "self approve"));

        assertEquals("发起人与复核人必须是不同用户", exception.getMessage());
        verify(auditWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvalUsesConditionalTransitionAndAppendsAudit() {
        AiGovernedChangeRequest executing = request(11L, GovernedChangeStatus.EXECUTING);
        executing.setCheckerId(12L);
        when(mapper.claimForExecution(anyString(), anyString(), any(), any(), any(), any()))
            .thenReturn(1);
        when(mapper.selectById("request-1")).thenReturn(executing);

        AiGovernedChangeRequest result = service.claim(
            "request-1", "tenant-a", 12L, "checker", "verified");

        assertEquals(GovernedChangeStatus.EXECUTING.name(), result.getStatus());
        verify(auditWriter).append(eq(result), eq("APPROVED"), eq(12L), eq("checker"),
            eq("verified"), any(LocalDateTime.class));
    }

    @Test
    void expiryAuditOnlyFollowsSuccessfulCas() {
        when(mapper.markExpired(anyString(), anyString(), any())).thenReturn(0);

        service.expire("request-1", "tenant-a");

        verify(auditWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    private AiGovernedChangeRequest request(Long makerId, GovernedChangeStatus status) {
        AiGovernedChangeRequest request = new AiGovernedChangeRequest();
        request.setId("request-1");
        request.setTenantId("tenant-a");
        request.setMakerId(makerId);
        request.setPayloadHash("a".repeat(64));
        request.setStatus(status.name());
        request.setExpiresAt(LocalDateTime.now().plusHours(1));
        return request;
    }

}
