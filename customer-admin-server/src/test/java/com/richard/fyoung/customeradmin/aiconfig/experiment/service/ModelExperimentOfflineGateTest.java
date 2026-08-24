package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentArm;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentOfflineEvalStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelCertificationService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.eval.service.EvalDatasetAdminService;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetRelease;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetReviewStatus;
import com.richard.fyoung.customerwork.capability.eval.EvalDatasetSnapshot;
import com.richard.fyoung.customerwork.capability.eval.EvalType;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelExperimentOfflineGateTest {

    @Test
    void shouldActivateOnlyAfterBothOfflineArmsPass() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            AiModelExperiment.class);
        AiModelExperimentMapper experimentMapper = mock(AiModelExperimentMapper.class);
        AiModelExperimentEventMapper eventMapper = mock(AiModelExperimentEventMapper.class);
        AiAgentMapper agentMapper = mock(AiAgentMapper.class);
        ModelConfigAccess modelAccess = mock(ModelConfigAccess.class);
        ModelCertificationService certificationService = mock(ModelCertificationService.class);
        ModelExperimentMetricsProvider metricsProvider = mock(ModelExperimentMetricsProvider.class);
        AdminTenantProperties tenantProperties = mock(AdminTenantProperties.class);
        ModelExperimentService service = new ModelExperimentService(experimentMapper, eventMapper,
            agentMapper, modelAccess, certificationService, metricsProvider, tenantProperties);

        EvalDatasetAdminService datasetService = mock(EvalDatasetAdminService.class);
        ModelExperimentArmEvaluationService evaluator = mock(ModelExperimentArmEvaluationService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service.configureOfflineEvaluation(datasetService, evaluator, transactionManager);

        AiModelExperiment experiment = experiment();
        when(experimentMapper.selectById(10L)).thenReturn(experiment);
        when(experimentMapper.update(any(), any())).thenReturn(1);
        when(experimentMapper.selectCount(any())).thenReturn(0L);
        AiAgent agent = new AiAgent();
        agent.setId(20L);
        agent.setModelId(3L);
        agent.setStatus(StatusFlags.ENABLED);
        when(agentMapper.selectById(20L)).thenReturn(agent);
        when(modelAccess.findVisibleAnyStateById(any())).thenAnswer(invocation ->
            deployment(invocation.getArgument(0)));

        EvalDatasetRelease release = new EvalDatasetRelease("release-1", EvalType.QUALITY,
            "quality-v1", "snapshot-1", "hash-1", 1, EvalDatasetReviewStatus.APPROVED,
            null, 1L, 2L, 1L, 2L);
        EvalDatasetSnapshot snapshot = new EvalDatasetSnapshot("snapshot-1", EvalType.QUALITY,
            "hash-1", 1, "[]", 1L);
        when(datasetService.requireApprovedQualityRelease("release-1")).thenReturn(release);
        when(datasetService.requireSnapshot("snapshot-1")).thenReturn(snapshot);
        ModelExperimentArmEvaluationService.ArmResult control =
            new ModelExperimentArmEvaluationService.ArmResult(
                ModelExperimentArm.CONTROL, true, 1L, "PASSED", null);
        ModelExperimentArmEvaluationService.ArmResult treatment =
            new ModelExperimentArmEvaluationService.ArmResult(
                ModelExperimentArm.TREATMENT, true, 2L, "PASSED", null);
        when(evaluator.evaluateBoth(experiment, agent, snapshot))
            .thenReturn(new ModelExperimentArmEvaluationService.GateResult(control, treatment));

        assertEquals(ModelExperimentStatus.RUNNING.name(), service.start(10L).getStatus());
        assertEquals(ModelExperimentOfflineEvalStatus.PASSED.name(), experiment.getOfflineEvalStatus());
        verify(evaluator).evaluateBoth(experiment, agent, snapshot);
    }

    private AiModelExperiment experiment() {
        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setId(10L);
        experiment.setTenantId("default");
        experiment.setAgentId(20L);
        experiment.setStatus(ModelExperimentStatus.DRAFT.name());
        experiment.setExpiresAt(LocalDateTime.now().plusDays(1));
        experiment.setControlDeploymentId(1L);
        experiment.setControlModelRef("model-1");
        experiment.setControlEndpointRevision(1);
        experiment.setTreatmentDeploymentId(2L);
        experiment.setTreatmentModelRef("model-2");
        experiment.setTreatmentEndpointRevision(1);
        experiment.setJudgeDeploymentId(3L);
        experiment.setJudgeModelRef("model-3");
        experiment.setJudgeEndpointRevision(1);
        experiment.setDatasetReleaseId("release-1");
        experiment.setDatasetVersionName("quality-v1");
        experiment.setDatasetSnapshotVersionId("snapshot-1");
        experiment.setDatasetContentHash("hash-1");
        experiment.setOfflineEvalStatus(ModelExperimentOfflineEvalStatus.NOT_STARTED.name());
        return experiment;
    }

    private AiModelConfig deployment(Long id) {
        AiModelConfig model = new AiModelConfig();
        model.setId(id);
        model.setModel("model-" + id);
        model.setEndpointRevision(1);
        model.setStatus(StatusFlags.ENABLED);
        model.setLifecycleStatus(ModelDeploymentLifecycle.ACTIVE.name());
        return model;
    }
}
