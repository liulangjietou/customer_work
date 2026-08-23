package com.richard.fyoung.customeradmin.aiconfig.model.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthErrorCategory;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelHealthStatus;
import com.richard.fyoung.customeradmin.aiconfig.model.domain.ModelProbeSource;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthEventVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelHealthSnapshotVO;
import com.richard.fyoung.customeradmin.aiconfig.model.dto.ModelTestResult;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelConfig;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthEvent;
import com.richard.fyoung.customeradmin.aiconfig.model.entity.AiModelHealthSnapshot;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthEventMapper;
import com.richard.fyoung.customeradmin.aiconfig.model.mapper.AiModelHealthSnapshotMapper;
import com.richard.fyoung.customeradmin.common.constant.ConnectivityTestStatus;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 健康快照和事件的一致性写入边界。 */
@Component
public class ModelHealthStore {

    private static final int UNHEALTHY_FAILURE_THRESHOLD = 3;
    private static final int DEFAULT_EVENT_LIMIT = 50;
    private static final int MAX_EVENT_LIMIT = 100;
    private static final long NEXT_PROBE_MINUTES = 5;

    private final AiModelHealthSnapshotMapper snapshotMapper;
    private final AiModelHealthEventMapper eventMapper;

    public ModelHealthStore(AiModelHealthSnapshotMapper snapshotMapper,
                            AiModelHealthEventMapper eventMapper) {
        this.snapshotMapper = snapshotMapper;
        this.eventMapper = eventMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public RecordResult record(AiModelConfig model, ModelTestResult result,
                               ModelProbeSource source) {
        boolean success = result.testStatus() == ConnectivityTestStatus.SUCCESS;
        LocalDateTime occurredAt = result.testTime() == null ? LocalDateTime.now() : result.testTime();

        AiModelHealthSnapshot next = new AiModelHealthSnapshot();
        next.setModelConfigId(model.getId());
        next.setTenantId(model.getTenantId());
        next.setHealthStatus(success ? ModelHealthStatus.HEALTHY.name() : ModelHealthStatus.DEGRADED.name());
        next.setAuthStatus(success ? "PASSED"
            : ModelHealthErrorCategory.AUTH.name().equals(category(result)) ? "FAILED" : "UNKNOWN");
        next.setCapabilityStatus("UNKNOWN");
        next.setConsecutiveFailures(success ? 0 : 1);
        next.setLastLatencyMs(result.latencyMs());
        next.setLastErrorCategory(success ? null : category(result));
        next.setLastMessage(result.message());
        next.setLastProbeAt(occurredAt);
        next.setLastSuccessAt(success ? occurredAt : null);
        next.setLastFailureAt(success ? null : occurredAt);
        next.setNextProbeAt(occurredAt.plusMinutes(NEXT_PROBE_MINUTES));
        next.setRevision(1);

        int inserted = CrossTenantOperations.execute(() -> snapshotMapper.insertIgnore(next));
        int updated = inserted > 0 ? 0 : CrossTenantOperations.execute(() ->
            snapshotMapper.updateIfNewer(next, success,
                !success && ModelHealthErrorCategory.AUTH.name().equals(category(result)),
                UNHEALTHY_FAILURE_THRESHOLD));
        AiModelHealthSnapshot authoritative = findSnapshot(model.getId(), model.getTenantId());
        AiModelHealthEvent event = event(model, result, source, success,
            authoritative == null ? next.getHealthStatus() : authoritative.getHealthStatus(), occurredAt);
        CrossTenantOperations.run(() -> eventMapper.insert(event));
        AiModelHealthSnapshot snapshot = authoritative == null ? next : authoritative;
        return new RecordResult(toVo(snapshot), inserted > 0 || updated > 0);
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
                .orderByDesc("occurred_at")
                .last("LIMIT " + limit)));
        return events.stream().map(this::toVo).toList();
    }

    private AiModelHealthSnapshot findSnapshot(Long modelId, String tenantId) {
        return CrossTenantOperations.execute(() -> snapshotMapper.selectOne(
            new QueryWrapper<AiModelHealthSnapshot>()
                .eq("model_config_id", modelId)
                .eq("tenant_id", tenantId)));
    }

    private AiModelHealthEvent event(AiModelConfig model, ModelTestResult result,
                                     ModelProbeSource source, boolean success,
                                     String healthStatus, LocalDateTime occurredAt) {
        AiModelHealthEvent event = new AiModelHealthEvent();
        event.setTenantId(model.getTenantId());
        event.setModelConfigId(model.getId());
        event.setSource(source.name());
        event.setProbeKind("CONNECTIVITY");
        event.setHealthStatus(healthStatus);
        event.setTestStatus(result.testStatus());
        event.setLatencyMs(result.latencyMs());
        event.setErrorCategory(success ? null : category(result));
        event.setMessage(result.message());
        event.setOccurredAt(occurredAt);
        return event;
    }

    private String category(ModelTestResult result) {
        return result.errorCategory() == null ? ModelHealthErrorCategory.UNKNOWN.name() : result.errorCategory();
    }

    private ModelHealthSnapshotVO unknown() {
        return new ModelHealthSnapshotVO(ModelHealthStatus.UNKNOWN.name(), "UNKNOWN", "UNKNOWN",
            0, null, null, null, null, null, null, null, 0);
    }

    private ModelHealthSnapshotVO toVo(AiModelHealthSnapshot snapshot) {
        return new ModelHealthSnapshotVO(snapshot.getHealthStatus(), snapshot.getAuthStatus(),
            snapshot.getCapabilityStatus(), snapshot.getConsecutiveFailures(), snapshot.getLastLatencyMs(),
            snapshot.getLastErrorCategory(), snapshot.getLastMessage(), snapshot.getLastProbeAt(),
            snapshot.getLastSuccessAt(), snapshot.getLastFailureAt(), snapshot.getNextProbeAt(),
            snapshot.getRevision());
    }

    private ModelHealthEventVO toVo(AiModelHealthEvent event) {
        return new ModelHealthEventVO(event.getId(), event.getSource(), event.getProbeKind(),
            event.getHealthStatus(), event.getTestStatus(), event.getLatencyMs(), event.getErrorCategory(),
            event.getMessage(), event.getOccurredAt());
    }

    /** applied=false 表示事件已审计，但更旧 probe 没有覆盖权威快照。 */
    public record RecordResult(ModelHealthSnapshotVO snapshot, boolean applied) {
    }
}
