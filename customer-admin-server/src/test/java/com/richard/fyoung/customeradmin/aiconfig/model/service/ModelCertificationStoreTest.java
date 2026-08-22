package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertification;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelCertificationRun;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelCertificationRunMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 认证运行永久入历史，但只有权威 current_run_id 命中才算晋级。 */
class ModelCertificationStoreTest {

    @Test
    void record_shouldReportPromotedOnlyWhenSnapshotPointsToThisAttempt() {
        AiModelCertificationRunMapper runMapper = mock(AiModelCertificationRunMapper.class);
        AiModelCertificationMapper certificationMapper = mock(AiModelCertificationMapper.class);
        ModelCertificationStore store = new ModelCertificationStore(runMapper, certificationMapper);
        AiModelCertificationRun run = run(100L);
        AiModelCertification current = new AiModelCertification();
        current.setCurrentRunId(100L);
        when(certificationMapper.selectById(7L)).thenReturn(current);

        ModelCertificationStore.RecordResult result = store.record(run, 3, 0, 20L);

        assertTrue(result.promoted());
        verify(runMapper).insert(run);
        verify(certificationMapper).promoteIfCurrent(any(AiModelCertification.class), eq(20L));
    }

    @Test
    void record_shouldRejectOlderAttemptWhenNewerSnapshotAlreadyWon() {
        AiModelCertificationRunMapper runMapper = mock(AiModelCertificationRunMapper.class);
        AiModelCertificationMapper certificationMapper = mock(AiModelCertificationMapper.class);
        ModelCertificationStore store = new ModelCertificationStore(runMapper, certificationMapper);
        AiModelCertification current = new AiModelCertification();
        current.setCurrentRunId(200L);
        when(certificationMapper.selectById(7L)).thenReturn(current);

        ModelCertificationStore.RecordResult result = store.record(run(100L), 3, 0, 20L);

        assertFalse(result.promoted());
    }

    private AiModelCertificationRun run(Long id) {
        AiModelCertificationRun run = new AiModelCertificationRun();
        run.setId(id);
        run.setModelConfigId(7L);
        run.setTenantId("tenant-a");
        run.setStatus("PASSED");
        run.setEndpointRevision(3);
        run.setSecretVersion(4);
        return run;
    }
}
