package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 只有改变路由投影的健康事件才生成可靠运行时发布任务。 */
class ModelHealthCoordinatorTest {

    @Test
    void record_shouldRefreshOnlyWhenRoutingProjectionChanges() {
        ModelHealthStore store = mock(ModelHealthStore.class);
        ModelHealthRouteRefreshService refreshService = mock(ModelHealthRouteRefreshService.class);
        ModelHealthCoordinator coordinator = new ModelHealthCoordinator(store, refreshService);
        AiModelConfig model = new AiModelConfig();
        ModelTestResult probe = mock(ModelTestResult.class);
        ModelHealthSnapshotVO snapshot = mock(ModelHealthSnapshotVO.class);
        when(store.record(model, probe, ModelProbeSource.SCHEDULED))
            .thenReturn(new ModelHealthStore.RecordResult(snapshot, true, false))
            .thenReturn(new ModelHealthStore.RecordResult(snapshot, true, true));

        coordinator.record(model, probe, ModelProbeSource.SCHEDULED);
        verify(refreshService, never()).refresh(model);
        coordinator.record(model, probe, ModelProbeSource.SCHEDULED);
        verify(refreshService).refresh(model);
    }
}
