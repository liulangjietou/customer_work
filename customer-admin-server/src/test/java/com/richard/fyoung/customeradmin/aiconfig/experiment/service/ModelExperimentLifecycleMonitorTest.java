package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.experiment.config.ModelExperimentLifecycleProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentLifecycleMonitorTest {

    @Test
    void oneExperimentFailure_shouldNotBlockLaterExperiment() {
        ModelExperimentLifecycleService lifecycleService = mock(ModelExperimentLifecycleService.class);
        when(lifecycleService.activeTargets()).thenReturn(List.of(
            new ModelExperimentLifecycleService.LifecycleTarget(1L, "tenant-a"),
            new ModelExperimentLifecycleService.LifecycleTarget(2L, "tenant-b")));
        doThrow(new IllegalStateException("metrics unavailable"))
            .when(lifecycleService).reconcile(1L);
        ModelExperimentLifecycleMonitor monitor = new ModelExperimentLifecycleMonitor(
            new ModelExperimentLifecycleProperties(), lifecycleService);

        monitor.reconcileSafely();

        verify(lifecycleService).reconcile(1L);
        verify(lifecycleService).reconcile(2L);
    }
}
