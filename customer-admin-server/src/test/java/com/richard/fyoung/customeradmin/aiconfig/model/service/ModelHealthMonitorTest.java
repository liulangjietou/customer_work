package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.config.ModelHealthMonitorProperties;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthSnapshotMapper;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 持续健康巡检的显式启用、批量限制和租户上下文传播测试。 */
class ModelHealthMonitorTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void disabledByDefault_shouldNotStartOrTouchDatabase() {
        ModelHealthMonitorProperties properties = new ModelHealthMonitorProperties();
        AiModelHealthSnapshotMapper mapper = mock(AiModelHealthSnapshotMapper.class);
        ModelHealthService healthService = mock(ModelHealthService.class);
        ModelHealthMonitor monitor = new ModelHealthMonitor(properties, mapper, healthService);

        monitor.start();
        monitor.destroy();

        verifyNoInteractions(mapper, healthService);
    }

    @Test
    void dispatch_shouldProbeEveryDueDeploymentInsideItsOwningTenant() {
        ModelHealthMonitorProperties properties = new ModelHealthMonitorProperties();
        properties.setBatchSize(200);
        AiModelHealthSnapshotMapper mapper = mock(AiModelHealthSnapshotMapper.class);
        ModelHealthService healthService = mock(ModelHealthService.class);
        AiModelConfig tenantA = model(1L, "tenant-a");
        AiModelConfig tenantB = model(2L, "tenant-b");
        when(mapper.findDueModels(100)).thenReturn(List.of(tenantA, tenantB));
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            assertEquals(id.equals(1L) ? "tenant-a" : "tenant-b", TenantContext.get());
            return CompletableFuture.completedFuture(mock(ModelTestResult.class));
        }).when(healthService).probe(org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.eq(ModelProbeSource.SCHEDULED));
        ModelHealthMonitor monitor = new ModelHealthMonitor(properties, mapper, healthService);

        monitor.dispatchSafely();

        verify(healthService).probe(1L, ModelProbeSource.SCHEDULED);
        verify(healthService).probe(2L, ModelProbeSource.SCHEDULED);
        assertNull(TenantContext.get());
    }

    private AiModelConfig model(Long id, String tenantId) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setTenantId(tenantId);
        return model;
    }
}
