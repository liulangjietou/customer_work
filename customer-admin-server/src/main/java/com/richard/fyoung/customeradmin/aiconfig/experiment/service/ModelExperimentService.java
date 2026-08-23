package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.agent.entity.AiAgent;
import com.richard.fyoung.customeradmin.aiconfig.agent.mapper.AiAgentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEventType;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentArmMetricsVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentCreateRequest;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentEventVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentMetricsVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.dto.ModelExperimentVO;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentEvent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelDeploymentLifecycle;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelCertificationService;
import com.richard.fyoung.customeradmin.aiconfig.model.service.ModelConfigAccess;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 在线模型双臂实验控制面。
 *
 * <p>定义一经创建即不可编辑；启动时重新校验 Agent、双臂 ACTIVE 状态、端点修订和当前认证，
 * 避免用已经漂移的部署执行旧实验。流量分桶与指标归属通过独立 Provider 接入。</p>
 */
@Service
public class ModelExperimentService {

    private static final int INITIAL_REVISION = 1;
    private static final int MAX_REASON_LENGTH = 500;

    private final AiModelExperimentMapper experimentMapper;
    private final AiModelExperimentEventMapper eventMapper;
    private final AiAgentMapper agentMapper;
    private final ModelConfigAccess modelConfigAccess;
    private final ModelCertificationService certificationService;
    private final ModelExperimentMetricsProvider metricsProvider;
    private final AdminTenantProperties tenantProperties;
    private final CustomerWorkConfigPublisher runtimePublisher;
    private final RuntimePublishTaskMapper publishTaskMapper;

    public ModelExperimentService(AiModelExperimentMapper experimentMapper,
                                  AiModelExperimentEventMapper eventMapper,
                                  AiAgentMapper agentMapper,
                                  ModelConfigAccess modelConfigAccess,
                                  ModelCertificationService certificationService,
                                  ModelExperimentMetricsProvider metricsProvider,
                                  AdminTenantProperties tenantProperties) {
        this(experimentMapper, eventMapper, agentMapper, modelConfigAccess, certificationService,
            metricsProvider, tenantProperties, null, null);
    }

    public ModelExperimentService(AiModelExperimentMapper experimentMapper,
                                  AiModelExperimentEventMapper eventMapper,
                                  AiAgentMapper agentMapper,
                                  ModelConfigAccess modelConfigAccess,
                                  ModelCertificationService certificationService,
                                  ModelExperimentMetricsProvider metricsProvider,
                                  AdminTenantProperties tenantProperties,
                                  CustomerWorkConfigPublisher runtimePublisher) {
        this(experimentMapper, eventMapper, agentMapper, modelConfigAccess, certificationService,
            metricsProvider, tenantProperties, runtimePublisher, null);
    }

    @Autowired
    public ModelExperimentService(AiModelExperimentMapper experimentMapper,
                                  AiModelExperimentEventMapper eventMapper,
                                  AiAgentMapper agentMapper,
                                  ModelConfigAccess modelConfigAccess,
                                  ModelCertificationService certificationService,
                                  ModelExperimentMetricsProvider metricsProvider,
                                  AdminTenantProperties tenantProperties,
                                  CustomerWorkConfigPublisher runtimePublisher,
                                  RuntimePublishTaskMapper publishTaskMapper) {
        this.experimentMapper = experimentMapper;
        this.eventMapper = eventMapper;
        this.agentMapper = agentMapper;
        this.modelConfigAccess = modelConfigAccess;
        this.certificationService = certificationService;
        this.metricsProvider = metricsProvider;
        this.tenantProperties = tenantProperties;
        this.runtimePublisher = runtimePublisher;
        this.publishTaskMapper = publishTaskMapper;
    }

    public List<ModelExperimentVO> list(Long agentId, String status) {
        LambdaQueryWrapper<AiModelExperiment> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null) {
            wrapper.eq(AiModelExperiment::getAgentId, agentId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AiModelExperiment::getStatus, requireStatus(status).name());
        }
        wrapper.orderByDesc(AiModelExperiment::getCreateTime);
        List<AiModelExperiment> experiments = experimentMapper.selectList(wrapper);
        Map<String, RuntimePublishTask> tasks = loadPublishTasks(experiments);
        return experiments.stream().map(item -> toVO(item, tasks)).toList();
    }

    public ModelExperimentVO get(Long id) {
        AiModelExperiment experiment = requireExperiment(id);
        return toVO(experiment, loadPublishTasks(List.of(experiment)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ModelExperimentVO create(ModelExperimentCreateRequest request) {
        if (Objects.equals(request.controlDeploymentId(), request.treatmentDeploymentId())) {
            throw new BizException(ResultCode.PARAM_INVALID, "对照组与实验组必须引用不同部署");
        }
        requireEnabledAgent(request.agentId());
        AiModelConfig control = requireVisibleDeployment(request.controlDeploymentId());
        AiModelConfig treatment = requireVisibleDeployment(request.treatmentDeploymentId());

        AiModelExperiment experiment = new AiModelExperiment();
        experiment.setTenantId(currentTenant());
        experiment.setExperimentCode("exp-" + UUID.randomUUID().toString().replace("-", ""));
        experiment.setExperimentName(request.experimentName().trim());
        experiment.setAgentId(request.agentId());
        experiment.setControlDeploymentId(control.getId());
        experiment.setControlModelRef(control.getModel());
        experiment.setControlEndpointRevision(endpointRevision(control));
        experiment.setTreatmentDeploymentId(treatment.getId());
        experiment.setTreatmentModelRef(treatment.getModel());
        experiment.setTreatmentEndpointRevision(endpointRevision(treatment));
        experiment.setRevision(INITIAL_REVISION);
        experiment.setAssignmentSalt(UUID.randomUUID().toString().replace("-", ""));
        experiment.setTreatmentBps(request.treatmentBps());
        experiment.setStatus(ModelExperimentStatus.DRAFT.name());
        experiment.setMinSample(request.minSample());
        experiment.setMaxErrorRate(request.maxErrorRate());
        experiment.setMaxP95LatencyMs(request.maxP95LatencyMs());
        experiment.setExpiresAt(request.expiresAt());
        experimentMapper.insert(experiment);
        return toVO(experiment);
    }

    /** 启动是有副作用的独立能力；定义漂移、认证失效或同 Agent 已运行实验都会 fail-fast。 */
    @Transactional(rollbackFor = Exception.class)
    public ModelExperimentVO start(Long id) {
        AiModelExperiment experiment = requireExperiment(id);
        requireState(experiment, ModelExperimentStatus.DRAFT);
        if (!experiment.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BizException(ResultCode.PARAM_INVALID, "实验已超过 expiresAt，不能启动");
        }
        requireEnabledAgent(experiment.getAgentId());
        validateArmForStart(experiment.getControlDeploymentId(), experiment.getControlModelRef(),
            experiment.getControlEndpointRevision(), "对照组");
        validateArmForStart(experiment.getTreatmentDeploymentId(), experiment.getTreatmentModelRef(),
            experiment.getTreatmentEndpointRevision(), "实验组");

        Long running = experimentMapper.selectCount(new LambdaQueryWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getAgentId, experiment.getAgentId())
            .eq(AiModelExperiment::getStatus, ModelExperimentStatus.RUNNING.name()));
        if (running != null && running > 0) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "该智能体已有 RUNNING 实验");
        }

        LocalDateTime now = LocalDateTime.now();
        try {
            int updated = experimentMapper.update(null, new LambdaUpdateWrapper<AiModelExperiment>()
                .eq(AiModelExperiment::getId, experiment.getId())
                .eq(AiModelExperiment::getStatus, ModelExperimentStatus.DRAFT.name())
                .set(AiModelExperiment::getStatus, ModelExperimentStatus.RUNNING.name())
                .set(AiModelExperiment::getStartedAt, now));
            assertTransition(updated, "实验状态已变化，请刷新后重试");
        } catch (DuplicateKeyException e) {
            throw new BizException(ResultCode.RESOURCE_DUPLICATE, "该智能体已有 RUNNING 实验");
        }
        experiment.setStatus(ModelExperimentStatus.RUNNING.name());
        experiment.setStartedAt(now);
        String taskId = publishRuntime(experiment, ModelExperimentPublishAction.ACTIVATE);
        persistTaskReference(experiment, taskId, ModelExperimentPublishAction.ACTIVATE,
            ModelExperimentStatus.RUNNING);
        appendEvent(experiment, ModelExperimentEventType.START,
            ModelExperimentStatus.DRAFT, ModelExperimentStatus.RUNNING, null, currentUserId(), now);
        return toVO(experiment);
    }

    /** 人工停止仅接受 RUNNING，且原因必须非空。 */
    @Transactional(rollbackFor = Exception.class)
    public ModelExperimentVO stop(Long id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(ResultCode.PARAM_INVALID, "停止原因不能为空");
        }
        return transitionRunningToStopped(requireExperiment(id), ModelExperimentEventType.STOP,
            truncate(reason.trim()), currentUserId());
    }

    public List<ModelExperimentEventVO> events(Long id) {
        AiModelExperiment experiment = requireExperiment(id);
        return eventMapper.selectList(new LambdaQueryWrapper<AiModelExperimentEvent>()
                .eq(AiModelExperimentEvent::getExperimentId, experiment.getId())
                .orderByDesc(AiModelExperimentEvent::getOccurredAt))
            .stream().map(event -> new ModelExperimentEventVO(
                event.getId(), event.getEventType(), event.getFromStatus(), event.getToStatus(),
                event.getReason(), event.getActorId(), event.getOccurredAt())).toList();
    }

    /** 只读指标接口；默认从调用日志读取真实实验曝光，缺少运行时 Provider 时明确返回等待状态。 */
    public ModelExperimentMetricsVO metrics(Long id) {
        AiModelExperiment experiment = requireExperiment(id);
        return toMetricsVO(experiment.getId(), metricsProvider.snapshot(experiment));
    }

    private ModelExperimentVO transitionRunningToStopped(AiModelExperiment experiment,
                                                          ModelExperimentEventType eventType,
                                                          String reason,
                                                          Long actorId) {
        requireState(experiment, ModelExperimentStatus.RUNNING);
        LocalDateTime now = LocalDateTime.now();
        int updated = experimentMapper.update(null, new LambdaUpdateWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getId, experiment.getId())
            .eq(AiModelExperiment::getStatus, ModelExperimentStatus.RUNNING.name())
            .set(AiModelExperiment::getStatus, ModelExperimentStatus.STOPPED.name())
            .set(AiModelExperiment::getStoppedAt, now)
            .set(AiModelExperiment::getStopReason, reason));
        assertTransition(updated, "实验已不处于 RUNNING 状态");
        experiment.setStatus(ModelExperimentStatus.STOPPED.name());
        experiment.setStoppedAt(now);
        experiment.setStopReason(reason);
        String taskId = publishRuntime(experiment, ModelExperimentPublishAction.DEACTIVATE);
        persistTaskReference(experiment, taskId, ModelExperimentPublishAction.DEACTIVATE,
            ModelExperimentStatus.STOPPED);
        appendEvent(experiment, eventType, ModelExperimentStatus.RUNNING,
            ModelExperimentStatus.STOPPED, reason, actorId, now);
        return toVO(experiment);
    }

    private void appendEvent(AiModelExperiment experiment,
                             ModelExperimentEventType eventType,
                             ModelExperimentStatus from,
                             ModelExperimentStatus to,
                             String reason,
                             Long actorId,
                             LocalDateTime occurredAt) {
        AiModelExperimentEvent event = new AiModelExperimentEvent();
        event.setTenantId(experiment.getTenantId());
        event.setExperimentId(experiment.getId());
        event.setEventType(eventType.name());
        event.setFromStatus(from.name());
        event.setToStatus(to.name());
        event.setReason(reason);
        event.setActorId(actorId);
        event.setOccurredAt(occurredAt);
        eventMapper.insert(event);
    }

    private void validateArmForStart(Long deploymentId, String modelRef,
                                     Integer endpointRevision, String armName) {
        AiModelConfig model = requireVisibleDeployment(deploymentId);
        if (!Integer.valueOf(StatusFlags.ENABLED).equals(model.getStatus())
            || !ModelDeploymentLifecycle.ACTIVE.name().equals(model.getLifecycleStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, armName + "部署不是 ACTIVE 启用态: " + deploymentId);
        }
        if (!Objects.equals(modelRef, model.getModel())
            || !Objects.equals(endpointRevision, endpointRevision(model))) {
            throw new BizException(ResultCode.PARAM_INVALID, armName + "部署配置已漂移，请创建新实验: " + deploymentId);
        }
        certificationService.requirePassedCurrent(model);
    }

    private AiAgent requireEnabledAgent(Long agentId) {
        AiAgent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (!Integer.valueOf(StatusFlags.ENABLED).equals(agent.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID, "智能体未启用: " + agentId);
        }
        return agent;
    }

    private AiModelConfig requireVisibleDeployment(Long deploymentId) {
        AiModelConfig model = modelConfigAccess.findVisibleAnyStateById(deploymentId);
        if (model == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型部署不存在或不可见: " + deploymentId);
        }
        if (!StringUtils.hasText(model.getModel())) {
            throw new BizException(ResultCode.PARAM_INVALID, "模型部署缺少模型标识: " + deploymentId);
        }
        return model;
    }

    private AiModelExperiment requireExperiment(Long id) {
        AiModelExperiment experiment = experimentMapper.selectById(id);
        if (experiment == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "模型实验不存在: " + id);
        }
        return experiment;
    }

    private void requireState(AiModelExperiment experiment, ModelExperimentStatus expected) {
        if (!expected.name().equals(experiment.getStatus())) {
            throw new BizException(ResultCode.PARAM_INVALID,
                "实验状态必须为 " + expected.name() + "，当前为 " + experiment.getStatus());
        }
    }

    private ModelExperimentStatus requireStatus(String status) {
        try {
            return ModelExperimentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "未知实验状态: " + status);
        }
    }

    private ModelExperimentMetricsVO toMetricsVO(Long experimentId,
                                                  ModelExperimentMetricsSnapshot snapshot) {
        return new ModelExperimentMetricsVO(experimentId, snapshot.availability().name(),
            snapshot.message(), snapshot.samples(), snapshot.errorRate(), snapshot.p95LatencyMs(),
            toArm(snapshot.control()), toArm(snapshot.treatment()), snapshot.evaluatedAt());
    }

    private ModelExperimentArmMetricsVO toArm(ModelExperimentMetricsSnapshot.Arm arm) {
        return arm == null ? null : new ModelExperimentArmMetricsVO(
            arm.samples(), arm.errorRate(), arm.p95LatencyMs());
    }

    private ModelExperimentVO toVO(AiModelExperiment source) {
        return toVO(source, loadPublishTasks(List.of(source)));
    }

    private ModelExperimentVO toVO(AiModelExperiment source,
                                   Map<String, RuntimePublishTask> publishTasks) {
        RuntimePublishTask activationTask = taskById(publishTasks, source.getActivationTaskId());
        RuntimePublishTask deactivationTask = taskById(publishTasks, source.getDeactivationTaskId());
        ModelExperimentEffectiveStateResolver.Resolution effective =
            ModelExperimentEffectiveStateResolver.resolve(source, activationTask, deactivationTask);
        ModelExperimentVO target = new ModelExperimentVO();
        target.setId(source.getId());
        target.setExperimentCode(source.getExperimentCode());
        target.setExperimentName(source.getExperimentName());
        target.setAgentId(source.getAgentId());
        target.setControlDeploymentId(source.getControlDeploymentId());
        target.setControlModelRef(source.getControlModelRef());
        target.setControlEndpointRevision(source.getControlEndpointRevision());
        target.setTreatmentDeploymentId(source.getTreatmentDeploymentId());
        target.setTreatmentModelRef(source.getTreatmentModelRef());
        target.setTreatmentEndpointRevision(source.getTreatmentEndpointRevision());
        target.setRevision(source.getRevision());
        target.setTreatmentBps(source.getTreatmentBps());
        target.setStatus(source.getStatus());
        target.setEffectiveState(effective.state().name());
        target.setActivationTaskId(source.getActivationTaskId());
        target.setActivationTaskStatus(statusOf(activationTask));
        target.setActivationTaskGateStatus(gateStatusOf(activationTask));
        target.setDeactivationTaskId(source.getDeactivationTaskId());
        target.setDeactivationTaskStatus(statusOf(deactivationTask));
        target.setDeactivationTaskGateStatus(gateStatusOf(deactivationTask));
        RuntimePublishTask effectiveTask = effective.referencedTask();
        target.setEffectiveTaskId(effectiveTask == null ? null : effectiveTask.getId());
        target.setEffectiveTaskStatus(statusOf(effectiveTask));
        target.setEffectiveTaskGateStatus(gateStatusOf(effectiveTask));
        target.setEffectiveTaskLastError(effectiveTask == null ? null : effectiveTask.getLastError());
        target.setMinSample(source.getMinSample());
        target.setMaxErrorRate(source.getMaxErrorRate());
        target.setMaxP95LatencyMs(source.getMaxP95LatencyMs());
        target.setExpiresAt(source.getExpiresAt());
        target.setStartedAt(source.getStartedAt());
        target.setStoppedAt(source.getStoppedAt());
        target.setCompletedAt(source.getCompletedAt());
        target.setStopReason(source.getStopReason());
        target.setCreateBy(source.getCreateBy());
        target.setCreateTime(source.getCreateTime());
        return target;
    }

    private Map<String, RuntimePublishTask> loadPublishTasks(List<AiModelExperiment> experiments) {
        if (publishTaskMapper == null || experiments == null || experiments.isEmpty()) {
            return Map.of();
        }
        Set<String> taskIds = experiments.stream()
            .flatMap(item -> java.util.stream.Stream.of(
                item.getActivationTaskId(), item.getDeactivationTaskId()))
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<RuntimePublishTask> tasks = publishTaskMapper.selectBatchIds(taskIds);
        if (tasks == null || tasks.isEmpty()) {
            return Map.of();
        }
        return tasks.stream().collect(Collectors.toMap(
            RuntimePublishTask::getId, task -> task, (left, right) -> left, LinkedHashMap::new));
    }

    private String statusOf(RuntimePublishTask task) {
        return task == null ? null : task.getStatus();
    }

    private RuntimePublishTask taskById(Map<String, RuntimePublishTask> tasks, String taskId) {
        return StringUtils.hasText(taskId) ? tasks.get(taskId) : null;
    }

    private String gateStatusOf(RuntimePublishTask task) {
        return task == null ? null : task.getGateStatus();
    }

    private int endpointRevision(AiModelConfig model) {
        return model.getEndpointRevision() == null ? INITIAL_REVISION : model.getEndpointRevision();
    }

    private void assertTransition(int updated, String message) {
        if (updated != 1) {
            throw new BizException(ResultCode.PARAM_INVALID, message);
        }
    }

    private String currentTenant() {
        if (!tenantProperties.isEnabled()) {
            return TenantContext.DEFAULT;
        }
        String tenant = TenantContext.get();
        if (!StringUtils.hasText(tenant)) {
            throw new BizException(ResultCode.FORBIDDEN, "缺少租户上下文，无法管理模型实验");
        }
        return TenantContext.canonicalizeTenantId(tenant);
    }

    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (SaTokenException e) {
            return null;
        }
    }

    private String truncate(String value) {
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }

    private String publishRuntime(AiModelExperiment experiment,
                                  ModelExperimentPublishAction action) {
        if (runtimePublisher == null) {
            return null;
        }
        String taskId = runtimePublisher.publishExperiment(
            experiment.getAgentId(), experiment.getId(), action);
        if (!StringUtils.hasText(taskId)) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED,
                "模型实验运行时发布任务未入队，请检查 Nacos 开关与启用渠道绑定");
        }
        return taskId;
    }

    private void persistTaskReference(AiModelExperiment experiment,
                                      String taskId,
                                      ModelExperimentPublishAction action,
                                      ModelExperimentStatus expectedStatus) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        LambdaUpdateWrapper<AiModelExperiment> update = new LambdaUpdateWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getId, experiment.getId())
            .eq(AiModelExperiment::getStatus, expectedStatus.name());
        if (action == ModelExperimentPublishAction.ACTIVATE) {
            update.set(AiModelExperiment::getActivationTaskId, taskId);
            experiment.setActivationTaskId(taskId);
        } else {
            update.set(AiModelExperiment::getDeactivationTaskId, taskId);
            experiment.setDeactivationTaskId(taskId);
        }
        assertTransition(experimentMapper.update(null, update), "实验发布任务关联失败，请刷新后重试");
    }
}
