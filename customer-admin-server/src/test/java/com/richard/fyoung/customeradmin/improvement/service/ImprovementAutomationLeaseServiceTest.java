package com.richard.fyoung.customeradmin.improvement.service;

import com.richard.fyoung.customeradmin.improvement.config.ImprovementAutomationProperties;
import com.richard.fyoung.customeradmin.improvement.entity.AgentImprovementCase;
import com.richard.fyoung.customeradmin.improvement.mapper.AgentImprovementCaseMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImprovementAutomationLeaseServiceTest {

    @Test
    void claimDue_shouldReturnOnlyRowsWonByDatabaseCas() {
        AgentImprovementCaseMapper mapper = mock(AgentImprovementCaseMapper.class);
        ImprovementAutomationProperties properties = new ImprovementAutomationProperties();
        properties.setBatchSize(20);
        properties.setLeaseMs(120000L);
        AgentImprovementCase first = row(1L);
        AgentImprovementCase second = row(2L);
        when(mapper.findDueCandidates(anyLong(), eq(20))).thenReturn(List.of(first, second));
        when(mapper.claim(eq(1L), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(mapper.claim(eq(2L), anyString(), anyLong(), anyLong())).thenReturn(0);

        List<AgentImprovementCase> claimed =
            new ImprovementAutomationLeaseService(mapper, properties).claimDue();

        assertEquals(List.of(first), claimed);
        assertNotNull(first.getLeaseOwner());
        assertTrue(first.getLeaseUntilMs() > System.currentTimeMillis());
    }

    private AgentImprovementCase row(Long id) {
        AgentImprovementCase row = new AgentImprovementCase();
        row.setId(id);
        row.setTenantId("tenant-a");
        return row;
    }
}
