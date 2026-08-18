package com.richard.fyoung.customeradmin.aiconfig.channel.publish.service;

import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 运行时配置发布任务与实例 ACK 的持久状态机。 */
@Service
public class RuntimePublishTaskService {

    private static final int MAX_BACKOFF_SHIFT = 10;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final RuntimePublishTaskMapper taskMapper;
    private final RuntimeConfigAckMapper ackMapper;
    private final RuntimePublishProperties properties;
    private final AdminTenantProperties tenantProperties;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    public RuntimePublishTaskService(RuntimePublishTaskMapper taskMapper, RuntimeConfigAckMapper ackMapper,
                                     RuntimePublishProperties properties,
                                     AdminTenantProperties tenantProperties) {
        this.taskMapper = taskMapper;
        this.ackMapper = ackMapper;
        this.properties = properties;
        this.tenantProperties = tenantProperties;
    }

    /** 与智能体/模型业务修改同事务写入，不在 afterCommit 内存回调中丢任务。 */
    @Transactional(rollbackFor = Exception.class)
    public String enqueueAgent(Long agentId) {
        Objects.requireNonNull(agentId, "agentId");
        long now = System.currentTimeMillis();
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(UUID.randomUUID().toString());
        task.setTenantId(currentTenant());
        task.setTargetId(agentId);
        task.setPublishScope("FULL");
        task.setStatus(RuntimePublishStatus.PENDING.name());
        task.setAttempts(0);
        task.setNextAttemptAtMs(now);
        task.setLeaseUntilMs(0L);
        task.setCreatedAtMs(now);
        task.setUpdatedAtMs(now);
        taskMapper.insert(task);
        return task.getId();
    }

    /** 跨租户扫描候选，再用 CAS 抢租约；多 admin Pod 不会并发执行同一条。 */
    @Transactional(rollbackFor = Exception.class)
    public List<RuntimePublishTask> claimDue() {
        long now = System.currentTimeMillis();
        List<RuntimePublishTask> candidates = CrossTenantOperations.execute(() ->
            taskMapper.findDueCandidates(now, properties.getBatchSize()));
        List<RuntimePublishTask> claimed = new ArrayList<>();
        for (RuntimePublishTask task : candidates) {
            int changed = CrossTenantOperations.execute(() -> taskMapper.claim(
                task.getId(), workerId, now, now + properties.getLeaseMs()));
            if (changed == 1) {
                task.setStatus(RuntimePublishStatus.PROCESSING.name());
                task.setLeaseOwner(workerId);
                task.setLeaseUntilMs(now + properties.getLeaseMs());
                claimed.add(task);
            }
        }
        return claimed;
    }

    public void attachMetadata(RuntimePublishTask task) {
        int changed = CrossTenantOperations.execute(() ->
            taskMapper.attachMetadata(task, workerId, System.currentTimeMillis()));
        requireLease(changed, task.getId());
    }

    public void markPublished(RuntimePublishTask task) {
        int changed = CrossTenantOperations.execute(() ->
            taskMapper.markPublished(task.getId(), workerId, System.currentTimeMillis()));
        requireLease(changed, task.getId());
        refreshAckStatus(task.getTenantId(), task.getRevision());
    }

    public void markDeliveryFailed(RuntimePublishTask task, Throwable failure) {
        int attempts = task.getAttempts() + 1;
        long now = System.currentTimeMillis();
        boolean exhausted = attempts >= properties.getMaxAttempts();
        long backoff = properties.getBaseBackoffMs() * (1L << Math.min(attempts, MAX_BACKOFF_SHIFT));
        String status = exhausted ? RuntimePublishStatus.FAILED.name() : RuntimePublishStatus.PENDING.name();
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        String finalMessage = message;
        int changed = CrossTenantOperations.execute(() -> taskMapper.markDeliveryFailed(
            task.getId(), workerId, status, attempts, exhausted ? now : now + backoff, finalMessage, now));
        requireLease(changed, task.getId());
    }

    /** ACK token 已在入口绑定租户；此处只在该租户内核对 revision/hash。 */
    @Transactional(rollbackFor = Exception.class)
    public RuntimePublishStatus recordAck(RuntimeConfigAck ack) {
        validateAck(ack);
        String tenantId = currentTenant();
        RuntimePublishTask task = taskMapper.findByRevision(tenantId, ack.revision());
        if (task == null) {
            throw new IllegalArgumentException("runtime publish revision not found");
        }
        if (!Objects.equals(task.getContentHash(), ack.contentHash())) {
            throw new IllegalArgumentException("runtime config content hash mismatch");
        }

        long now = System.currentTimeMillis();
        RuntimeConfigAckEntity entity = new RuntimeConfigAckEntity();
        entity.setTenantId(tenantId);
        entity.setRevision(ack.revision());
        entity.setContentHash(ack.contentHash());
        entity.setInstanceId(ack.instanceId());
        entity.setStatus(ack.status());
        entity.setReason(ack.reason());
        entity.setAppliedAtMs(ack.appliedAtMs());
        entity.setCreatedAtMs(now);
        entity.setUpdatedAtMs(now);
        ackMapper.upsert(entity);

        return refreshAckStatus(tenantId, ack.revision());
    }

    private RuntimePublishStatus refreshAckStatus(String tenantId, String revision) {
        int applied = ackMapper.countByStatus(tenantId, revision, "APPLIED");
        int rejected = ackMapper.countByStatus(tenantId, revision, "REJECTED");
        if (applied == 0 && rejected == 0) {
            return RuntimePublishStatus.PUBLISHED;
        }
        RuntimePublishStatus aggregate;
        if (rejected > 0) {
            aggregate = applied > 0 ? RuntimePublishStatus.PARTIAL : RuntimePublishStatus.FAILED;
        } else if (applied >= properties.getMinimumAckCount()) {
            aggregate = RuntimePublishStatus.APPLIED;
        } else {
            aggregate = RuntimePublishStatus.PARTIAL;
        }
        taskMapper.updateAckStatus(tenantId, revision, aggregate.name(), System.currentTimeMillis());
        return aggregate;
    }

    private void validateAck(RuntimeConfigAck ack) {
        if (ack == null || !StringUtils.hasText(ack.revision()) || !StringUtils.hasText(ack.contentHash())
            || !StringUtils.hasText(ack.instanceId())) {
            throw new IllegalArgumentException("revision, contentHash and instanceId are required");
        }
        if (!"APPLIED".equals(ack.status()) && !"REJECTED".equals(ack.status())) {
            throw new IllegalArgumentException("unsupported runtime config ACK status");
        }
    }

    private String currentTenant() {
        if (TenantContext.isPresent()) {
            return TenantContext.get();
        }
        if (tenantProperties.isEnabled()) {
            return TenantContext.require();
        }
        return TenantContext.DEFAULT;
    }

    private void requireLease(int changed, String taskId) {
        if (changed != 1) {
            throw new IllegalStateException("runtime publish lease lost: " + taskId);
        }
    }
}
