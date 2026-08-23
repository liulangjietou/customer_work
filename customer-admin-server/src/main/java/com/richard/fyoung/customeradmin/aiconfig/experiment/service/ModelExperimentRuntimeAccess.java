package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * RUNNING 在线实验的运行时快照唯一读取入口。
 *
 * <p>启动后的部署仍可能被停用、轮换凭据或改变端点；每次发布都在这里重新校验两臂，
 * 保证控制面状态不会绕过运行时部署门禁。</p>
 */
@Component
public class ModelExperimentRuntimeAccess {

    private static final int INITIAL_ENDPOINT_REVISION = 1;

    private final AiModelExperimentMapper experimentMapper;
    private final ModelConfigAccess modelConfigAccess;
    private final SecretRefService secretRefService;
    private final AdminTenantProperties tenantProperties;

    public ModelExperimentRuntimeAccess(AiModelExperimentMapper experimentMapper,
                                        ModelConfigAccess modelConfigAccess,
                                        SecretRefService secretRefService,
                                        AdminTenantProperties tenantProperties) {
        this.experimentMapper = experimentMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.secretRefService = secretRefService;
        this.tenantProperties = tenantProperties;
    }

    /** 当前 Agent 没有有效 RUNNING 实验时返回 null，发布端据此回到基线模型。 */
    public CustomerWorkRuntimeConfig.OnlineExperiment runningForAgent(Long agentId) {
        if (agentId == null) {
            return null;
        }
        AiModelExperiment experiment = experimentMapper.selectOne(scopedRunningQuery()
            .eq(AiModelExperiment::getAgentId, agentId));
        if (experiment == null || experiment.getExpiresAt() == null
            || !experiment.getExpiresAt().isAfter(LocalDateTime.now())) {
            return null;
        }
        return toRuntime(experiment);
    }

    /** 激活任务只允许发布它创建时绑定的实验；状态已推进时由 Worker 将其判定为 superseded。 */
    public CustomerWorkRuntimeConfig.OnlineExperiment requireRunning(Long agentId, Long experimentId) {
        if (agentId == null || experimentId == null) {
            throw new ExperimentActivationSupersededException(experimentId, agentId);
        }
        AiModelExperiment experiment = experimentMapper.selectOne(scopedRunningQuery()
            .eq(AiModelExperiment::getId, experimentId)
            .eq(AiModelExperiment::getAgentId, agentId));
        if (experiment == null || experiment.getExpiresAt() == null
            || !experiment.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ExperimentActivationSupersededException(experimentId, agentId);
        }
        return toRuntime(experiment);
    }

    private LambdaQueryWrapper<AiModelExperiment> scopedRunningQuery() {
        LambdaQueryWrapper<AiModelExperiment> query = new LambdaQueryWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getStatus, ModelExperimentStatus.RUNNING.name());
        if (tenantProperties.isEnabled()) {
            query.eq(AiModelExperiment::getTenantId, currentTenant());
        }
        return query;
    }

    private CustomerWorkRuntimeConfig.OnlineExperiment toRuntime(AiModelExperiment experiment) {
        CustomerWorkRuntimeConfig.OnlineExperiment runtime =
            new CustomerWorkRuntimeConfig.OnlineExperiment();
        runtime.setExperimentId(experiment.getId());
        runtime.setRevision(experiment.getRevision());
        runtime.setAssignmentSalt(experiment.getAssignmentSalt());
        runtime.setTreatmentBps(experiment.getTreatmentBps());
        runtime.setExpiresAtEpochMs(experiment.getExpiresAt().atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli());
        runtime.setControl(toArm("CONTROL", experiment.getControlDeploymentId(),
            experiment.getControlModelRef(), experiment.getControlEndpointRevision()));
        runtime.setTreatment(toArm("TREATMENT", experiment.getTreatmentDeploymentId(),
            experiment.getTreatmentModelRef(), experiment.getTreatmentEndpointRevision()));
        return runtime;
    }

    private CustomerWorkRuntimeConfig.ExperimentArm toArm(String armName,
                                                           Long deploymentId,
                                                           String expectedModel,
                                                           Integer expectedRevision) {
        AiModelConfig deployment = modelConfigAccess.findVisibleById(deploymentId);
        if (deployment == null) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "在线实验部署当前不可运行: " + deploymentId);
        }
        int actualRevision = deployment.getEndpointRevision() == null
            ? INITIAL_ENDPOINT_REVISION : deployment.getEndpointRevision();
        if (!Objects.equals(expectedModel, deployment.getModel())
            || !Objects.equals(expectedRevision, actualRevision)) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "在线实验部署配置已漂移: " + deploymentId);
        }
        CustomerWorkRuntimeConfig.ExperimentArm arm = new CustomerWorkRuntimeConfig.ExperimentArm();
        arm.setArm(armName);
        arm.setDeploymentId(deployment.getId());
        arm.setProvider(deployment.getProvider());
        arm.setName(deployment.getModel());
        arm.setBaseUrl(deployment.getBaseUrl());
        arm.setEndpointRevision(actualRevision);
        arm.setApiKeyCipher(secretRefService.resolveCipherText(
            deployment.getSecretRefId(), deployment.getTenantId(), deployment.getApiKey()));
        return arm;
    }

    private String currentTenant() {
        // 发布 Worker 没有 Sa-Token 会话，租户真源是其进入任务时建立的 TenantContext。
        String tenant = TenantContext.get();
        if (!StringUtils.hasText(tenant)) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法读取在线实验");
        }
        return TenantContext.canonicalizeTenantId(tenant);
    }
}
