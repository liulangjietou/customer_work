package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentMetricsAvailability;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.workspace.callstats.config.AppAgentCallStatsGatewayProvider;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsExtMapper;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.AgentCallStatsGateway;
import com.richard.fyoung.customeradmin.workspace.callstats.jdbc.ModelExperimentMetricsRow;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallLogModelExperimentMetricsProviderTest {

    private AppAgentCallStatsGatewayProvider gatewayProvider;
    private AgentCallStatsExtMapper extMapper;
    private CallLogModelExperimentMetricsProvider provider;

    @BeforeEach
    void setUp() {
        gatewayProvider = mock(AppAgentCallStatsGatewayProvider.class);
        AgentCallStatsGateway gateway = mock(AgentCallStatsGateway.class);
        extMapper = mock(AgentCallStatsExtMapper.class);
        when(gatewayProvider.get()).thenReturn(gateway);
        when(gateway.extMapper()).thenReturn(extMapper);
        provider = new CallLogModelExperimentMetricsProvider(gatewayProvider);
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void snapshot_shouldAggregateBothArmsAndPassExplicitTenantWindow() {
        AiModelExperiment experiment = experiment();
        when(extMapper.experimentMetrics(eq("tenant-a"), eq(77L), eq(4), anyLong(), anyLong()))
            .thenReturn(List.of(row("CONTROL", 80L, 4L, 120L),
                row("TREATMENT", 20L, 2L, 220L)));

        ModelExperimentMetricsSnapshot snapshot = provider.snapshot(experiment);

        assertEquals(ModelExperimentMetricsAvailability.READY, snapshot.availability());
        assertEquals(100L, snapshot.samples());
        assertEquals(new BigDecimal("0.0600000"), snapshot.errorRate());
        assertEquals(80L, snapshot.control().samples());
        assertEquals(new BigDecimal("0.0500000"), snapshot.control().errorRate());
        assertEquals(120L, snapshot.control().p95LatencyMs());
        assertEquals(20L, snapshot.treatment().samples());
        assertEquals(new BigDecimal("0.1000000"), snapshot.treatment().errorRate());
        assertEquals(220L, snapshot.treatment().p95LatencyMs());
        assertNull(snapshot.p95LatencyMs(), "总 P95 不应用单臂 P95 近似拼接");
        assertNotNull(snapshot.evaluatedAt());
        verify(extMapper).experimentMetrics(eq("tenant-a"), eq(77L), eq(4), anyLong(), anyLong());
    }

    @Test
    void snapshot_withoutExposure_shouldReturnReadyZeroSampleSemantics() {
        AiModelExperiment experiment = experiment();
        when(extMapper.experimentMetrics(eq("tenant-a"), eq(77L), eq(4), anyLong(), anyLong()))
            .thenReturn(List.of());

        ModelExperimentMetricsSnapshot snapshot = provider.snapshot(experiment);

        assertTrue(snapshot.isReady());
        assertEquals(0L, snapshot.samples());
        assertEquals(new BigDecimal("0.0000000"), snapshot.errorRate());
        assertEquals(0L, snapshot.control().samples());
        assertEquals(0L, snapshot.treatment().samples());
        assertEquals("运行时已接入，当前尚无实验曝光样本", snapshot.message());
    }

    private AiModelExperiment experiment() {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(77L);
        experiment.setRevision(4);
        experiment.setStartedAt(LocalDateTime.now().minusMinutes(10));
        experiment.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return experiment;
    }

    private ModelExperimentMetricsRow row(String arm, Long samples, Long errors, Long p95LatencyMs) {
        ModelExperimentMetricsRow row = new ModelExperimentMetricsRow();
        row.setArm(arm);
        row.setSamples(samples);
        row.setErrors(errors);
        row.setP95LatencyMs(p95LatencyMs);
        return row;
    }
}
