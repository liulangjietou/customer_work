package com.richard.fyoung.customeradmin.governance.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.governance.change.GovernanceProperties;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernanceAuditEvent;
import com.richard.fyoung.customeradmin.governance.change.entity.AiGovernedChangeRequest;
import com.richard.fyoung.customeradmin.governance.change.mapper.GovernanceAuditEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GovernanceAuditWriterTest {

    @Test
    void appendsGenesisLinkedEventWithEnforcedRetention() {
        GovernanceAuditEventMapper mapper = mock(GovernanceAuditEventMapper.class);
        GovernanceProperties properties = new GovernanceProperties();
        properties.setAuditRetentionDays(1);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(mapper.insert(any(AiGovernanceAuditEvent.class))).thenReturn(1);
        GovernanceAuditWriter writer = new GovernanceAuditWriter(mapper, properties);
        AiGovernedChangeRequest request = new AiGovernedChangeRequest();
        request.setId("request-1");
        request.setTenantId("tenant-a");
        request.setPayloadHash("b".repeat(64));
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 10, 0);

        writer.append(request, "SUBMITTED", 11L, "maker", "target", now);

        ArgumentCaptor<AiGovernanceAuditEvent> captor =
            ArgumentCaptor.forClass(AiGovernanceAuditEvent.class);
        verify(mapper).insert(captor.capture());
        AiGovernanceAuditEvent event = captor.getValue();
        assertEquals(1, event.getSequenceNo());
        assertEquals("0".repeat(64), event.getPreviousHash());
        assertEquals(64, event.getEventHash().length());
        assertTrue(event.getRetentionUntil().compareTo(now.plusDays(365)) >= 0);
    }
}
