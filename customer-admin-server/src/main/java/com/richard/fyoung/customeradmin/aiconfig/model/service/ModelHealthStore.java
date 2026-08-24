package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthEventType;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthOverrideMode;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthEventVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthOverrideRequest;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthEvent;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthSnapshotMapper;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 健康快照状态机和追加事件的一致性事务边界。 */
@Component
public class ModelHealthStore {

    private static final int DEFAULT_EVENT_LIMIT = 50;
    private static final int MAX_EVENT_LIMIT = 100;

    private final AiModelHealthSnapshotMapper snapshotMapper;
    private final AiModelHealthEventMapper eventMapper;
    private final ModelHealthStateMachine stateMachine;

    public ModelHealthStore(AiModelHealthSnapshotMapper snapshotMapper,
                            AiModelHealthEventMapper eventMapper,
                            ModelHealthStateMachine stateMachine) {
        this.snapshotMapper = snapshotMapper;
        this.eventMapper = eventMapper;
        this.stateMachine = stateMachine;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecordResult record(AiModelConfig model, ModelTestResult result,
                               ModelProbeSource source) {
        LocalDateTime occurredAt = result.testTime() == null ? LocalDateTime.now() : result.testTime();
        AiModelHealthSnapshot current = lock(model);
        ModelHealthStateMachine.Overlay before = stateMachine.overlay(current, occurredAt);
        String previousStatus = before.healthStatus();
        boolean applied;

        if (current == null) {
            current = stateMachine.initial(model, result, occurredAt);
            AiModelHealthSnapshot initial = current;
            int inserted = CrossTenantOperations.execute(() -> snapshotMapper.insertIgnore(initial));
            if (inserted == 1) {
                applied = true;
            } else {
                current = requireLocked(model);
                applied = applyIfNewer(current, result, occurredAt);
            }
        } else {
            applied = applyIfNewer(current, result, occurredAt);
        }

        ModelHealthStateMachine.Overlay after = stateMachine.overlay(current, occurredAt);
        ModelHealthEventType eventType = !applied ? ModelHealthEventType.STALE_PROBE
            : Objects.equals(previousStatus, after.healthStatus())
                ? ModelHealthEventType.PROBE : ModelHealthEventType.STATE_TRANSITION;
        insertEvent(probeEvent(model, result, source, eventType, previousStatus, current, after,
            occurredAt));
        return new RecordResult(toVo(current), applied,
            applied && !before.routingEquivalent(after));
    }

    @Transactional(rollbackFor = Exception.class)
    public RecordResult override(AiModelConfig model, ModelHealthOverrideRequest request,
                                 Long operatorId, String operatorName) {
        LocalDateTime now = LocalDateTime.now();
        AiModelHealthSnapshot current = lock(model);
        if (current == null) {
            current = stateMachine.unknown(model, now);
            applyOverride(current, request, operatorId, operatorName);
            AiModelHealthSnapshot initial = current;
            int inserted = CrossTenantOperations.execute(() -> snapshotMapper.insertIgnore(initial));
            if (inserted != 1) {
                current = requireLocked(model);
            } else {
                ModelHealthStateMachine.Overlay after = stateMachine.overlay(current, now);
                insertEvent(overrideEvent(model, request, operatorId, operatorName,
                    ModelHealthStatus.UNKNOWN.name(), current, after, now));
                return new RecordResult(toVo(current), true, true);
            }
        }

        ModelHealthStateMachine.Overlay before = stateMachine.overlay(current, now);
        String previousStatus = current.getHealthStatus();
        applyOverride(current, request, operatorId, operatorName);
        update(current);
        ModelHealthStateMachine.Overlay after = stateMachine.overlay(current, now);
        insertEvent(overrideEvent(model, request, operatorId, operatorName,
            previousStatus, current, after, now));
        return new RecordResult(toVo(current), true, !before.routingEquivalent(after));
    }

    /** 多副本可重复调用；行锁内只有第一个清理者会写事件并触发路由刷新。 */
    @Transactional(rollbackFor = Exception.class)
    public RecordResult expireOverride(AiModelConfig model, LocalDateTime now) {
        AiModelHealthSnapshot current = lock(model);
        if (current == null || !stateMachine.hasExpiredOverride(current, now)) {
            return new RecordResult(current == null ? unknown() : toVo(current), false, false);
        }
        LocalDateTime activeAt = current.getOverrideUntil().minusNanos(1_000);
        ModelHealthStateMachine.Overlay before = stateMachine.overlay(current, activeAt);
        String previousStatus = current.getHealthStatus();
        String previousMode = current.getOverrideMode();
        String reason = current.getOverrideReason();
        clearOverride(current);
        current.setRevision(value(current.getRevision()) + 1);
        update(current);
        ModelHealthStateMachine.Overlay after = stateMachine.overlay(current, now);

        AiModelHealthEvent event = baseEvent(model, ModelHealthEventType.OVERRIDE_EXPIRED,
            ModelProbeSource.SCHEDULED, "ROUTING_OVERRIDE", previousStatus, current, after, now);
        event.setOverrideMode(previousMode);
        event.setMessage(reason);
        insertEvent(event);
        return new RecordResult(toVo(current), true, !before.routingEquivalent(after));
    }

    public List<AiModelConfig> findExpiredOverrideModels(int requestedLimit, LocalDateTime now) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return CrossTenantOperations.execute(() ->
            snapshotMapper.findExpiredOverrideModels(now, limit));
    }

    public ModelHealthSnapshotVO get(AiModelConfig model) {
        AiModelHealthSnapshot snapshot = findSnapshot(model.getId(), model.getTenantId());
        return snapshot == null ? unknown() : toVo(snapshot);
    }

    public Map<Long, ModelHealthSnapshotVO> findByModels(Collection<AiModelConfig> models) {
        if (CollectionUtils.isEmpty(models)) {
            return Collections.emptyMap();
        }
        List<Long> ids = models.stream().map(AiModelConfig::getId).distinct().toList();
        List<String> tenants = models.stream().map(AiModelConfig::getTenantId).distinct().toList();
        List<AiModelHealthSnapshot> snapshots = CrossTenantOperations.execute(() -> snapshotMapper.selectList(
            new QueryWrapper<AiModelHealthSnapshot>()
                .in("model_config_id", ids)
                .in("tenant_id", tenants)));
        return snapshots.stream().collect(Collectors.toMap(AiModelHealthSnapshot::getModelConfigId,
            this::toVo, (left, right) -> left));
    }

    public List<ModelHealthEventVO> listEvents(AiModelConfig model, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_EVENT_LIMIT
            : Math.max(1, Math.min(requestedLimit, MAX_EVENT_LIMIT));
        List<AiModelHealthEvent> events = CrossTenantOperations.execute(() -> eventMapper.selectList(
            new QueryWrapper<AiModelHealthEvent>()
                .eq("tenant_id", model.getTenantId())
                .eq("model_config_id", model.getId())
                .orderByDesc("occurred_at", "id")
                .last("LIMIT " + limit)));
        return events.stream().map(this::toVo).toList();
    }

    private boolean applyIfNewer(AiModelHealthSnapshot current, ModelTestResult result,
                                 LocalDateTime occurredAt) {
        if (current.getLastProbeAt() != null && !occurredAt.isAfter(current.getLastProbeAt())) {
            return false;
        }
        stateMachine.applyProbe(current, result, occurredAt);
        update(current);
        return true;
    }

    private void applyOverride(AiModelHealthSnapshot snapshot, ModelHealthOverrideRequest request,
                               Long operatorId, String operatorName) {
        if (request.mode() == ModelHealthOverrideMode.AUTO) {
            clearOverride(snapshot);
        } else {
            snapshot.setOverrideMode(request.mode().name());
            snapshot.setOverrideReason(request.reason().trim());
            snapshot.setOverrideOperatorId(operatorId);
            snapshot.setOverrideOperatorName(operatorName);
            snapshot.setOverrideUntil(request.expiresAt());
        }
        snapshot.setRevision(value(snapshot.getRevision()) + 1);
    }

    private void clearOverride(AiModelHealthSnapshot snapshot) {
        snapshot.setOverrideMode(ModelHealthOverrideMode.AUTO.name());
        snapshot.setOverrideReason(null);
        snapshot.setOverrideOperatorId(null);
        snapshot.setOverrideOperatorName(null);
        snapshot.setOverrideUntil(null);
    }

    private AiModelHealthSnapshot lock(AiModelConfig model) {
        return CrossTenantOperations.execute(() ->
            snapshotMapper.lockSnapshot(model.getId(), model.getTenantId()));
    }

    private AiModelHealthSnapshot requireLocked(AiModelConfig model) {
        AiModelHealthSnapshot snapshot = lock(model);
        if (snapshot == null) {
            throw new IllegalStateException("model health snapshot disappeared during update");
        }
        return snapshot;
    }

    private void update(AiModelHealthSnapshot snapshot) {
        int updated = CrossTenantOperations.execute(() -> snapshotMapper.updateById(snapshot));
        if (updated != 1) {
            throw new IllegalStateException("model health snapshot update failed");
        }
    }

    private AiModelHealthSnapshot findSnapshot(Long modelId, String tenantId) {
        return CrossTenantOperations.execute(() -> snapshotMapper.selectOne(
            new QueryWrapper<AiModelHealthSnapshot>()
                .eq("model_config_id", modelId)
                .eq("tenant_id", tenantId)));
    }

    private AiModelHealthEvent probeEvent(AiModelConfig model, ModelTestResult result,
                                          ModelProbeSource source, ModelHealthEventType eventType,
                                          String previousStatus, AiModelHealthSnapshot snapshot,
                                          ModelHealthStateMachine.Overlay overlay,
                                          LocalDateTime occurredAt) {
        AiModelHealthEvent event = baseEvent(model, eventType, source, "CONNECTIVITY",
            previousStatus, snapshot, overlay, occurredAt);
        event.setTestStatus(result.testStatus());
        event.setLatencyMs(result.latencyMs());
        event.setErrorCategory(result.errorCategory());
        event.setMessage(result.message());
        return event;
    }

    private AiModelHealthEvent overrideEvent(AiModelConfig model,
                                             ModelHealthOverrideRequest request,
                                             Long operatorId, String operatorName,
                                             String previousStatus,
                                             AiModelHealthSnapshot snapshot,
                                             ModelHealthStateMachine.Overlay overlay,
                                             LocalDateTime occurredAt) {
        ModelHealthEventType eventType = request.mode() == ModelHealthOverrideMode.AUTO
            ? ModelHealthEventType.OVERRIDE_CLEARED : ModelHealthEventType.OVERRIDE_SET;
        AiModelHealthEvent event = baseEvent(model, eventType, ModelProbeSource.MANUAL,
            "ROUTING_OVERRIDE", previousStatus, snapshot, overlay, occurredAt);
        event.setOverrideMode(request.mode().name());
        event.setOperatorId(operatorId);
        event.setOperatorName(operatorName);
        event.setMessage(request.reason().trim());
        return event;
    }

    private AiModelHealthEvent baseEvent(AiModelConfig model,
                                         ModelHealthEventType eventType,
                                         ModelProbeSource source,
                                         String probeKind,
                                         String previousStatus,
                                         AiModelHealthSnapshot snapshot,
                                         ModelHealthStateMachine.Overlay overlay,
                                         LocalDateTime occurredAt) {
        AiModelHealthEvent event = new AiModelHealthEvent();
        event.setTenantId(model.getTenantId());
        event.setModelConfigId(model.getId());
        event.setEventType(eventType.name());
        event.setSource(source.name());
        event.setProbeKind(probeKind);
        event.setPreviousHealthStatus(previousStatus);
        event.setHealthStatus(snapshot.getHealthStatus());
        event.setEffectiveHealthStatus(overlay.effectiveHealthStatus());
        event.setOverrideMode(overlay.overrideMode());
        event.setOccurredAt(occurredAt);
        return event;
    }

    private void insertEvent(AiModelHealthEvent event) {
        int inserted = CrossTenantOperations.execute(() -> eventMapper.insert(event));
        if (inserted != 1) {
            throw new IllegalStateException("model health event insert failed");
        }
    }

    private ModelHealthSnapshotVO unknown() {
        return toVo((AiModelHealthSnapshot) null);
    }

    private ModelHealthSnapshotVO toVo(AiModelHealthSnapshot snapshot) {
        ModelHealthStateMachine.Overlay overlay = stateMachine.overlay(snapshot, LocalDateTime.now());
        if (snapshot == null) {
            return new ModelHealthSnapshotVO(ModelHealthStatus.UNKNOWN.name(),
                overlay.effectiveHealthStatus(), overlay.routingAvailable(), "UNKNOWN", "UNKNOWN",
                0, 0, null, null, null, null, null, null, null, null,
                ModelHealthOverrideMode.AUTO.name(), null, null, null, null, 0);
        }
        return new ModelHealthSnapshotVO(snapshot.getHealthStatus(), overlay.effectiveHealthStatus(),
            overlay.routingAvailable(), snapshot.getAuthStatus(), snapshot.getCapabilityStatus(),
            snapshot.getConsecutiveFailures(), snapshot.getConsecutiveSuccesses(),
            snapshot.getLastLatencyMs(), snapshot.getLastErrorCategory(), snapshot.getLastMessage(),
            snapshot.getLastProbeAt(), snapshot.getLastSuccessAt(), snapshot.getLastFailureAt(),
            snapshot.getNextProbeAt(), snapshot.getCooldownUntil(), overlay.overrideMode(),
            snapshot.getOverrideReason(), snapshot.getOverrideOperatorId(),
            snapshot.getOverrideOperatorName(), snapshot.getOverrideUntil(), snapshot.getRevision());
    }

    private ModelHealthEventVO toVo(AiModelHealthEvent event) {
        return new ModelHealthEventVO(event.getId(), event.getEventType(), event.getSource(),
            event.getProbeKind(), event.getPreviousHealthStatus(), event.getHealthStatus(),
            event.getEffectiveHealthStatus(), event.getOverrideMode(), event.getOperatorId(),
            event.getOperatorName(), event.getTestStatus(), event.getLatencyMs(),
            event.getErrorCategory(), event.getMessage(), event.getOccurredAt());
    }

    private int value(Integer raw) {
        return raw == null ? 0 : raw;
    }

    /** applied=false 表示事件已追加，但旧 probe/重复过期清理没有覆盖权威快照。 */
    public record RecordResult(ModelHealthSnapshotVO snapshot,
                               boolean applied,
                               boolean routingChanged) {

        public RecordResult(ModelHealthSnapshotVO snapshot, boolean applied) {
            this(snapshot, applied, false);
        }
    }
}
