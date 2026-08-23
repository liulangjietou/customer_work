package com.richard.fyoung.customeradmin.aiconfig.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentEventType;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentStatus;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperiment;
import com.richard.fyoung.customeradmin.aiconfig.experiment.entity.AiModelExperimentEvent;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.mapper.AiModelExperimentMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.CustomerWorkConfigPublisher;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 到期与护栏状态推进。
 *
 * <p>扫描与单条推进拆开：Monitor 在每个租户上下文中通过本 Bean 的代理调用
 * {@link #reconcile(Long)}，从而让每个实验拥有独立事务。单条聚合失败或并发人工停止不会回滚
 * 其它实验已经写入的事件。</p>
 */
@Service
public class ModelExperimentLifecycleService {

    private static final int MAX_REASON_LENGTH = 500;
    private static final String EXPIRED_REASON = "实验达到 expiresAt，已自动完成";

    private final AiModelExperimentMapper experimentMapper;
    private final AiModelExperimentEventMapper eventMapper;
    private final ModelExperimentMetricsProvider metricsProvider;
    private final CustomerWorkConfigPublisher runtimePublisher;

    public ModelExperimentLifecycleService(AiModelExperimentMapper experimentMapper,
                                           AiModelExperimentEventMapper eventMapper,
                                           ModelExperimentMetricsProvider metricsProvider) {
        this(experimentMapper, eventMapper, metricsProvider, null);
    }

    @Autowired
    public ModelExperimentLifecycleService(AiModelExperimentMapper experimentMapper,
                                           AiModelExperimentEventMapper eventMapper,
                                           ModelExperimentMetricsProvider metricsProvider,
                                           CustomerWorkConfigPublisher runtimePublisher) {
        this.experimentMapper = experimentMapper;
        this.eventMapper = eventMapper;
        this.metricsProvider = metricsProvider;
        this.runtimePublisher = runtimePublisher;
    }

    /** 跨租户只枚举最小定位信息，具体读取和写入回到所属租户上下文。 */
    public List<LifecycleTarget> activeTargets() {
        return CrossTenantOperations.execute(() -> experimentMapper.selectList(
                new LambdaQueryWrapper<AiModelExperiment>()
                    .in(AiModelExperiment::getStatus,
                        ModelExperimentStatus.DRAFT.name(), ModelExperimentStatus.RUNNING.name())
                    .orderByAsc(AiModelExperiment::getExpiresAt)))
            .stream().map(item -> new LifecycleTarget(item.getId(), item.getTenantId())).toList();
    }

    /** 单实验独立事务：DRAFT 错过截止时间也会形成可审计 EXPIRED，而不是永久滞留。 */
    @Transactional(rollbackFor = Exception.class)
    public void reconcile(Long id) {
        AiModelExperiment experiment = experimentMapper.selectById(id);
        if (experiment == null) {
            return;
        }
        ModelExperimentStatus status = currentStatus(experiment.getStatus());
        if (status != ModelExperimentStatus.DRAFT && status != ModelExperimentStatus.RUNNING) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (!experiment.getExpiresAt().isAfter(now)) {
            completeExpired(experiment, status, now);
            return;
        }
        if (status == ModelExperimentStatus.DRAFT) {
            return;
        }

        ModelExperimentMetricsSnapshot metrics = metricsProvider.snapshot(experiment);
        Long samples = treatmentSamples(metrics);
        if (!metrics.isReady() || samples == null || samples < experiment.getMinSample()) {
            return;
        }
        String violation = guardrailViolation(experiment, metrics);
        if (violation != null) {
            autoStop(experiment, violation, now);
        }
    }

    private void completeExpired(AiModelExperiment experiment,
                                 ModelExperimentStatus from,
                                 LocalDateTime now) {
        int updated = experimentMapper.update(null, new LambdaUpdateWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getId, experiment.getId())
            .eq(AiModelExperiment::getStatus, from.name())
            .set(AiModelExperiment::getStatus, ModelExperimentStatus.COMPLETED.name())
            .set(AiModelExperiment::getCompletedAt, now)
            .set(AiModelExperiment::getStopReason, EXPIRED_REASON));
        if (updated == 1) {
            experiment.setStatus(ModelExperimentStatus.COMPLETED.name());
            experiment.setCompletedAt(now);
            experiment.setStopReason(EXPIRED_REASON);
            if (from == ModelExperimentStatus.RUNNING) {
                String taskId = publishRuntime(experiment);
                persistDeactivationTask(experiment, taskId, ModelExperimentStatus.COMPLETED);
            }
            appendEvent(experiment, ModelExperimentEventType.EXPIRED, from,
                ModelExperimentStatus.COMPLETED, EXPIRED_REASON, now);
        }
    }

    private void autoStop(AiModelExperiment experiment, String reason, LocalDateTime now) {
        int updated = experimentMapper.update(null, new LambdaUpdateWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getId, experiment.getId())
            .eq(AiModelExperiment::getStatus, ModelExperimentStatus.RUNNING.name())
            .set(AiModelExperiment::getStatus, ModelExperimentStatus.STOPPED.name())
            .set(AiModelExperiment::getStoppedAt, now)
            .set(AiModelExperiment::getStopReason, reason));
        if (updated == 1) {
            experiment.setStatus(ModelExperimentStatus.STOPPED.name());
            experiment.setStoppedAt(now);
            experiment.setStopReason(reason);
            String taskId = publishRuntime(experiment);
            persistDeactivationTask(experiment, taskId, ModelExperimentStatus.STOPPED);
            appendEvent(experiment, ModelExperimentEventType.AUTO_STOP,
                ModelExperimentStatus.RUNNING, ModelExperimentStatus.STOPPED, reason, now);
        }
    }

    private void appendEvent(AiModelExperiment experiment,
                             ModelExperimentEventType eventType,
                             ModelExperimentStatus from,
                             ModelExperimentStatus to,
                             String reason,
                             LocalDateTime occurredAt) {
        AiModelExperimentEvent event = new AiModelExperimentEvent();
        event.setTenantId(experiment.getTenantId());
        event.setExperimentId(experiment.getId());
        event.setEventType(eventType.name());
        event.setFromStatus(from.name());
        event.setToStatus(to.name());
        event.setReason(reason);
        event.setActorId(null);
        event.setOccurredAt(occurredAt);
        eventMapper.insert(event);
    }

    /** 护栏保护候选臂：有单臂聚合时以 treatment 为准，兼容首版仅提供总体聚合的 Provider。 */
    private String guardrailViolation(AiModelExperiment experiment,
                                      ModelExperimentMetricsSnapshot metrics) {
        BigDecimal errorRate = treatmentErrorRate(metrics);
        if (errorRate != null && errorRate.compareTo(experiment.getMaxErrorRate()) > 0) {
            return truncate("实验组错误率护栏触发: " + errorRate
                + " > " + experiment.getMaxErrorRate());
        }
        Long p95LatencyMs = treatmentP95(metrics);
        if (p95LatencyMs != null && p95LatencyMs > experiment.getMaxP95LatencyMs()) {
            return truncate("实验组 P95 延迟护栏触发: " + p95LatencyMs
                + "ms > " + experiment.getMaxP95LatencyMs() + "ms");
        }
        return null;
    }

    private Long treatmentSamples(ModelExperimentMetricsSnapshot metrics) {
        return metrics.treatment() != null && metrics.treatment().samples() != null
            ? metrics.treatment().samples() : metrics.samples();
    }

    private BigDecimal treatmentErrorRate(ModelExperimentMetricsSnapshot metrics) {
        return metrics.treatment() != null && metrics.treatment().errorRate() != null
            ? metrics.treatment().errorRate() : metrics.errorRate();
    }

    private Long treatmentP95(ModelExperimentMetricsSnapshot metrics) {
        return metrics.treatment() != null && metrics.treatment().p95LatencyMs() != null
            ? metrics.treatment().p95LatencyMs() : metrics.p95LatencyMs();
    }

    private ModelExperimentStatus currentStatus(String value) {
        return ModelExperimentStatus.valueOf(value);
    }

    private String truncate(String value) {
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH);
    }

    private String publishRuntime(AiModelExperiment experiment) {
        if (runtimePublisher == null) {
            return null;
        }
        String taskId = runtimePublisher.publishExperiment(
            experiment.getAgentId(), experiment.getId(), ModelExperimentPublishAction.DEACTIVATE);
        if (!StringUtils.hasText(taskId)) {
            throw new BizException(ResultCode.RUNTIME_PUBLISH_FAILED,
                "模型实验撤流任务未入队，请检查 Nacos 开关与启用渠道绑定");
        }
        return taskId;
    }

    private void persistDeactivationTask(AiModelExperiment experiment,
                                         String taskId,
                                         ModelExperimentStatus expectedStatus) {
        if (!StringUtils.hasText(taskId)) {
            return;
        }
        int updated = experimentMapper.update(null, new LambdaUpdateWrapper<AiModelExperiment>()
            .eq(AiModelExperiment::getId, experiment.getId())
            .eq(AiModelExperiment::getStatus, expectedStatus.name())
            .set(AiModelExperiment::getDeactivationTaskId, taskId));
        if (updated != 1) {
            throw new BizException(ResultCode.PARAM_INVALID, "实验撤流任务关联失败，请刷新后重试");
        }
        experiment.setDeactivationTaskId(taskId);
    }

    /** 跨租户扫描只传递 ID 与租户，不传递可变实体。 */
    public record LifecycleTarget(Long experimentId, String tenantId) {
    }
}
