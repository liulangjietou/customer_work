package com.richard.fyoung.customeradmin.tenant.access.service;

import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPublishProperties;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessPublishStatus;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessSnapshot;
import com.richard.fyoung.customeradmin.tenant.access.TenantAccessDeliveryPlan;
import com.richard.fyoung.customeradmin.tenant.access.dto.TenantAccessDeliveryVO;
import com.richard.fyoung.customeradmin.tenant.access.entity.TenantAccessPublishTask;
import com.richard.fyoung.customeradmin.tenant.access.mapper.TenantAccessPublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 租户访问快照发布任务的持久状态机。 */
@Service
public class TenantAccessPublishTaskService {

    private static final int MAX_BACKOFF_SHIFT = 10;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TenantAccessPublishTaskMapper taskMapper;
    private final TenantAccessPublishProperties properties;
    private final RuntimePublishProperties runtimePublishProperties;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    public TenantAccessPublishTaskService(TenantAccessPublishTaskMapper taskMapper,
                                          TenantAccessPublishProperties properties,
                                          RuntimePublishProperties runtimePublishProperties) {
        this.taskMapper = taskMapper;
        this.properties = properties;
        this.runtimePublishProperties = runtimePublishProperties;
    }

    /** 必须在租户生命周期事务内调用，使状态、epoch 和发布任务同提交同回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public String enqueue(TenantAccessSnapshot snapshot) {
        return enqueue(snapshot, TenantAccessDeliveryPlan.provision());
    }

    @Transactional(rollbackFor = Exception.class)
    public String enqueue(TenantAccessSnapshot snapshot, TenantAccessDeliveryPlan plan) {
        return TenantContext.callWith(snapshot.tenantId(), () -> doEnqueue(snapshot, plan));
    }

    private String doEnqueue(TenantAccessSnapshot snapshot, TenantAccessDeliveryPlan plan) {
        long now = System.currentTimeMillis();
        taskMapper.supersedePending(snapshot.tenantId(), now);

        TenantAccessPublishTask task = new TenantAccessPublishTask();
        task.setId(UUID.randomUUID().toString());
        task.setTenantId(snapshot.tenantId());
        task.setTenantStatus(snapshot.status());
        task.setAccessEpoch(snapshot.accessEpoch());
        task.setOperation(plan.operation().name());
        task.setSessionRevocationStatus(plan.sessionRevocationStatus().name());
        task.setChannelDisableStatus(plan.channelDisableStatus().name());
        task.setChannelsDisabledCount(plan.channelsDisabledCount());
        task.setExpireTime(snapshot.expireTime());
        task.setDataId(dataIdFor(snapshot.tenantId()));
        task.setGroupName(runtimePublishProperties.getNacos().getGroup());
        task.setStatus(TenantAccessPublishStatus.PENDING.name());
        task.setAttempts(0);
        task.setNextAttemptAtMs(now);
        task.setLeaseUntilMs(0L);
        task.setCreatedAtMs(now);
        task.setUpdatedAtMs(now);
        taskMapper.insert(task);
        return task.getId();
    }

    /** 跨租户找任务并以 CAS + 唯一租约键抢占，同一租户同一时刻最多一个发布者。 */
    @Transactional(rollbackFor = Exception.class)
    public List<TenantAccessPublishTask> claimDue() {
        long now = System.currentTimeMillis();
        List<TenantAccessPublishTask> candidates = CrossTenantOperations.execute(() ->
            taskMapper.findDueCandidates(now, properties.getBatchSize()));
        List<TenantAccessPublishTask> claimed = new ArrayList<>();
        for (TenantAccessPublishTask task : candidates) {
            try {
                int changed = CrossTenantOperations.execute(() -> taskMapper.claim(
                    task.getId(), task.getTenantId(), workerId, now, now + properties.getLeaseMs()));
                if (changed == 1) {
                    task.setStatus(TenantAccessPublishStatus.PROCESSING.name());
                    task.setActiveLeaseKey(task.getTenantId());
                    task.setLeaseOwner(workerId);
                    task.setLeaseUntilMs(now + properties.getLeaseMs());
                    claimed.add(task);
                }
            } catch (DuplicateKeyException ignored) {
                // 另一个 Pod 已抢到同租户租约，留给下一轮扫描。
            }
        }
        return claimed;
    }

    public void markPublished(TenantAccessPublishTask task) {
        int changed = CrossTenantOperations.execute(() ->
            taskMapper.markPublished(task.getId(), workerId, System.currentTimeMillis()));
        requireLease(changed, task.getId());
    }

    public void markDeliveryFailed(TenantAccessPublishTask task, Throwable failure) {
        int attempts = task.getAttempts() + 1;
        long now = System.currentTimeMillis();
        boolean superseded = CrossTenantOperations.execute(() ->
            taskMapper.countNewerTasks(task.getTenantId(), task.getSeq())) > 0;
        boolean exhausted = properties.getMaxAttempts() > 0 && attempts >= properties.getMaxAttempts();
        String status = superseded
            ? TenantAccessPublishStatus.SUPERSEDED.name()
            : exhausted ? TenantAccessPublishStatus.FAILED.name() : TenantAccessPublishStatus.PENDING.name();
        long shiftedBackoff = properties.getBaseBackoffMs()
            * (1L << Math.min(attempts, MAX_BACKOFF_SHIFT));
        long backoff = Math.min(shiftedBackoff, properties.getMaxBackoffMs());
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        String finalMessage = message;
        int changed = CrossTenantOperations.execute(() -> taskMapper.markDeliveryFailed(
            task.getId(), workerId, status, attempts,
            superseded || exhausted ? now : now + backoff, finalMessage, now));
        requireLease(changed, task.getId());
    }

    public TenantAccessDeliveryVO latest(String tenantId) {
        TenantAccessPublishTask task = CrossTenantOperations.execute(() -> taskMapper.findLatest(tenantId));
        if (task == null) {
            return null;
        }
        TenantAccessDeliveryVO vo = new TenantAccessDeliveryVO();
        vo.setTaskId(task.getId());
        vo.setTenantId(task.getTenantId());
        vo.setTenantStatus(task.getTenantStatus());
        vo.setAccessEpoch(task.getAccessEpoch());
        vo.setOperation(task.getOperation());
        vo.setOrchestrationStatus(orchestrationStatus(task));
        vo.setSessionRevocationStatus(task.getSessionRevocationStatus());
        vo.setChannelDisableStatus(task.getChannelDisableStatus());
        vo.setChannelsDisabledCount(task.getChannelsDisabledCount());
        vo.setDataId(task.getDataId());
        vo.setStatus(task.getStatus());
        vo.setAttempts(task.getAttempts());
        vo.setLastError(task.getLastError());
        vo.setCreatedAtMs(task.getCreatedAtMs());
        vo.setUpdatedAtMs(task.getUpdatedAtMs());
        vo.setPublishedAtMs(task.getPublishedAtMs());
        return vo;
    }

    private String orchestrationStatus(TenantAccessPublishTask task) {
        if (TenantAccessPublishStatus.PUBLISHED.name().equals(task.getStatus())) {
            return "COMPLETED";
        }
        if (TenantAccessPublishStatus.FAILED.name().equals(task.getStatus())) {
            return "FAILED";
        }
        return "IN_PROGRESS";
    }

    private String dataIdFor(String tenantId) {
        if (!TenantContext.isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("tenantId format is invalid");
        }
        return properties.getDataId() + "-tenant-" + TenantContext.canonicalizeTenantId(tenantId);
    }

    private void requireLease(int changed, String taskId) {
        if (changed != 1) {
            throw new IllegalStateException("tenant access publish lease lost: " + taskId);
        }
    }
}
