package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.aiconfig.secret.service.SecretRefService;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkRuntimeConfig;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentRuntimeAccessTest {

    private AiModelExperimentMapper experimentMapper;
    private ModelConfigAccess modelConfigAccess;
    private SecretRefService secretRefService;
    private ModelExperimentRuntimeAccess access;

    /** 纯 Mockito 单测不启动 Mapper，解析 LambdaQueryWrapper 前需显式初始化实体列元数据。 */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new Configuration(), ""), AiModelExperiment.class);
    }

    @BeforeEach
    void setUp() {
        experimentMapper = mock(AiModelExperimentMapper.class);
        modelConfigAccess = mock(ModelConfigAccess.class);
        secretRefService = mock(SecretRefService.class);
        AdminTenantProperties tenantProperties = new AdminTenantProperties();
        tenantProperties.setEnabled(true);
        access = new ModelExperimentRuntimeAccess(
            experimentMapper, modelConfigAccess, secretRefService, tenantProperties);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void runningForAgent_shouldUseExplicitTenantRunningScopeAndCurrentSecretRefs() {
        AiModelExperiment experiment = runningExperiment();
        when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(experiment);
        when(modelConfigAccess.findVisibleById(11L)).thenReturn(deployment(
            11L, "control-model", 3, 101L, "LEGACY_CONTROL"));
        when(modelConfigAccess.findVisibleById(12L)).thenReturn(deployment(
            12L, "treatment-model", 5, 102L, "LEGACY_TREATMENT"));
        when(secretRefService.resolveCipherText(101L, "tenant-a", "LEGACY_CONTROL"))
            .thenReturn("CURRENT_CONTROL_CIPHER");
        when(secretRefService.resolveCipherText(102L, "tenant-a", "LEGACY_TREATMENT"))
            .thenReturn("CURRENT_TREATMENT_CIPHER");

        TenantContext.set("tenant-a");
        CustomerWorkRuntimeConfig.OnlineExperiment runtime = access.runningForAgent(7L);

        assertEquals(77L, runtime.getExperimentId());
        assertEquals(4, runtime.getRevision());
        assertEquals("CURRENT_CONTROL_CIPHER", runtime.getControl().getApiKeyCipher());
        assertEquals("CURRENT_TREATMENT_CIPHER", runtime.getTreatment().getApiKeyCipher());
        assertEquals(3, runtime.getControl().getEndpointRevision());
        assertEquals(5, runtime.getTreatment().getEndpointRevision());

        ArgumentCaptor<LambdaQueryWrapper<AiModelExperiment>> query =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(experimentMapper).selectOne(query.capture());
        String sql = query.getValue().getSqlSegment();
        assertTrue(sql.contains("tenant_id") || sql.contains("tenantId"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("tenant-a"));
        assertTrue(query.getValue().getParamNameValuePairs()
            .containsValue(ModelExperimentStatus.RUNNING.name()));
    }

    @Test
    void expiredRunningExperiment_shouldReturnBaselineWithoutResolvingDeployments() {
        AiModelExperiment expired = runningExperiment();
        expired.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(expired);

        TenantContext.set("tenant-a");
        assertNull(access.runningForAgent(7L));

        verify(modelConfigAccess, never()).findVisibleById(any());
        verify(secretRefService, never()).resolveCipherText(any(), any(), any());
    }

    @Test
    void deploymentRevisionOrModelDrift_shouldRejectBeforeReadingSecret() {
        when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(runningExperiment());
        when(modelConfigAccess.findVisibleById(11L)).thenReturn(deployment(
            11L, "control-model", 99, 101L, "LEGACY_CONTROL"));

        TenantContext.set("tenant-a");
        BizException error = assertThrows(BizException.class,
            () -> access.runningForAgent(7L));
        assertEquals(ResultCode.PARAM_INVALID, error.getResultCode());

        verify(secretRefService, never()).resolveCipherText(any(), any(), any());
        verify(modelConfigAccess, never()).findVisibleById(12L);
    }

    @Test
    void missingTenantContext_shouldFailClosedBeforeQuery() {
        BizException error = assertThrows(BizException.class,
            () -> access.runningForAgent(7L));
        assertEquals(ResultCode.FORBIDDEN, error.getResultCode());

        verify(experimentMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void activationTask_shouldFailAsSupersededWhenItsExperimentIsNoLongerRunning() {
        TenantContext.set("tenant-a");
        when(experimentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(ExperimentActivationSupersededException.class,
            () -> access.requireRunning(7L, 77L));

        verify(modelConfigAccess, never()).findVisibleById(any());
        verify(secretRefService, never()).resolveCipherText(any(), any(), any());
    }

    private AiModelExperiment runningExperiment() {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(77L);
        experiment.setTenantId("tenant-a");
        experiment.setAgentId(7L);
        experiment.setControlDeploymentId(11L);
        experiment.setControlModelRef("control-model");
        experiment.setControlEndpointRevision(3);
        experiment.setTreatmentDeploymentId(12L);
        experiment.setTreatmentModelRef("treatment-model");
        experiment.setTreatmentEndpointRevision(5);
        experiment.setRevision(4);
        experiment.setAssignmentSalt("salt-123");
        experiment.setTreatmentBps(2500);
        experiment.setStatus(ModelExperimentStatus.RUNNING.name());
        experiment.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return experiment;
    }

    private AiModelConfig deployment(Long id, String model, Integer endpointRevision,
                                     Long secretRefId, String legacyCipher) {
        AiModelConfig deployment = new AiModelConfig();
        deployment.setId(id);
        deployment.setTenantId("tenant-a");
        deployment.setProvider("openai");
        deployment.setModel(model);
        deployment.setBaseUrl("https://model.example");
        deployment.setEndpointRevision(endpointRevision);
        deployment.setSecretRefId(secretRefId);
        deployment.setApiKey(legacyCipher);
        return deployment;
    }
}
