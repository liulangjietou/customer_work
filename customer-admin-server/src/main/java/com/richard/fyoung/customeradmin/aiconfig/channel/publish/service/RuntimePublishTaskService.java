package com.richard.fyoung.customeradmin.aiconfig.channel.publish.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.RuntimePublishProperties;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishIntent;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishLeaseLostException;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.RuntimePublishStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.gate.EvalGateStatus;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimeConfigAckEntity;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.entity.RuntimePublishTask;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimeConfigAckMapper;
import com.richard.fyoung.customeradmin.aiconfig.channel.publish.mapper.RuntimePublishTaskMapper;
import com.richard.fyoung.customeradmin.aiconfig.experiment.domain.ModelExperimentPublishAction;
import com.richard.fyoung.customeradmin.configversion.service.RuntimeRollbackPatchExtractor;
import com.richard.fyoung.customeradmin.tenant.AdminTenantProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigAck;
import com.richard.fyoung.customerwork.safety.tenant.CrossTenantOperations;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
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
    private static final String CONTENT_CHANGED_ERROR =
        "runtime config changed while an older publish task was pending";

    private final RuntimePublishTaskMapper taskMapper;
    private final RuntimeConfigAckMapper ackMapper;
    private final RuntimePublishProperties properties;
    private final AdminTenantProperties tenantProperties;
    private final RuntimeRollbackPatchExtractor rollbackPatchExtractor =
        new RuntimeRollbackPatchExtractor(new ObjectMapper());
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
        return enqueue(agentId, null, null);
    }

    /** 实验生命周期变更与不可变发布意图同事务写入，避免 Worker 从后续状态反推动作。 */
    @Transactional(rollbackFor = Exception.class)
    public String enqueueExperiment(Long agentId, Long experimentId,
                                    ModelExperimentPublishAction action) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(experimentId, "experimentId");
        Objects.requireNonNull(action, "action");
        return enqueue(agentId, experimentId, action);
    }

    private String enqueue(Long agentId, Long experimentId, ModelExperimentPublishAction action) {
        Objects.requireNonNull(agentId, "agentId");
        long now = System.currentTimeMillis();
        RuntimePublishTask task = new RuntimePublishTask();
        task.setId(UUID.randomUUID().toString());
        task.setOperationId(task.getId());
        task.setPublishIntent(RuntimePublishIntent.NORMAL.name());
        task.setTenantId(currentTenant());
        task.setTargetId(agentId);
        task.setDataId(resolveTaskDataId(task.getTenantId()));
        task.setGroupName(resolveTaskGroupName());
        task.setExperimentId(experimentId);
        task.setExperimentPublishAction(action == null ? null : action.name());
        task.setPublishScope("FULL");
        task.setStatus(RuntimePublishStatus.PENDING.name());
        task.setGateStatus(action == ModelExperimentPublishAction.DEACTIVATE
            ? EvalGateStatus.NOT_REQUIRED.name() : EvalGateStatus.PENDING.name());
        task.setAttempts(0);
        task.setNextAttemptAtMs(now);
        task.setLeaseUntilMs(0L);
        task.setCreatedAtMs(now);
        task.setUpdatedAtMs(now);
        taskMapper.insert(task);
        return task.getId();
    }

    /**
     * 安全回滚/灰度在所有目标租户预校验完成后一次性入队。
     *
     * <p>方法本身是唯一事务边界；任意目标 insert 失败会回滚整批。任务只保存白名单补丁，
     * 不复制历史模型密文、MCP 请求头、路由或实验盐。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<RuntimePublishTask> enqueueSafe(SafePublishCommand command) {
        validateSafeCommand(command);
        long now = System.currentTimeMillis();
        List<RuntimePublishTask> tasks = new ArrayList<>();
        for (SafePublishTarget target : command.targets()) {
            RuntimePublishTask task = new RuntimePublishTask();
            task.setId(UUID.randomUUID().toString());
            task.setOperationId(command.operationId());
            task.setPublishIntent(command.publishIntent().name());
            task.setTenantId(target.tenantId());
            task.setTargetId(target.agentId());
            task.setDataId(resolveTaskDataId(target.tenantId()));
            task.setGroupName(resolveTaskGroupName());
            task.setSourceConfigVersionId(command.sourceConfigVersionId());
            task.setSourceContentHash(command.sourceContentHash());
            task.setRollbackPatchJson(command.rollbackPatchJson());
            task.setPublishScope(command.publishIntent() == RuntimePublishIntent.SAFE_GRAY
                ? "GRAY" : "FULL");
            task.setGrayTenants(command.publishIntent() == RuntimePublishIntent.SAFE_GRAY
                ? command.grayTenantsJson() : null);
            task.setSourceVersion(command.sourceVersion());
            task.setRemark(command.remark());
            task.setStatus(RuntimePublishStatus.PENDING.name());
            task.setGateStatus(EvalGateStatus.PENDING.name());
            task.setAttempts(0);
            task.setNextAttemptAtMs(now);
            task.setLeaseUntilMs(0L);
            task.setCreatedAtMs(now);
            task.setUpdatedAtMs(now);
            int inserted = TenantContext.callWith(target.tenantId(), () -> taskMapper.insert(task));
            if (inserted != 1) {
                throw new IllegalStateException("safe runtime publish task insert failed");
            }
            tasks.add(task);
        }
        return List.copyOf(tasks);
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

    /**
     * 在数据库行锁保护下校验租约、完成外部发布并提交终态。
     *
     * <p>Nacos 没有带 fencing token 的条件发布接口，单独做一次 CAS 后再外写仍有 TOCTOU。
     * 因此这里把 {@code SELECT ... FOR UPDATE} 行锁一直持有到外写和状态提交完成：旧 worker
     * 一旦被新 owner 抢占就无法通过校验；校验通过后，抢占方也只能等待本事务释放行锁。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishWithFencing(RuntimePublishTask task, Runnable publishAction) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(publishAction, "publishAction");
        long now = System.currentTimeMillis();
        RuntimePublishTask leased = CrossTenantOperations.execute(() ->
            taskMapper.lockLeaseForPublish(task.getId(), workerId, now));
        if (leased == null) {
            throw new RuntimePublishLeaseLostException(task.getId());
        }
        publishAction.run();
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

    /**
     * 固化快照与当前权威配置不一致时立即结束任务。
     * 同 Nacos 键有更新意图时标记 SUPERSEDED，否则标记 FAILED 暴露入队链路缺口；
     * 两种情况都不得按网络故障指数退避。
     */
    public void markContentChangedTerminal(RuntimePublishTask task) {
        int changed = CrossTenantOperations.execute(() -> taskMapper.markContentChangedTerminal(
            task.getId(), workerId, CONTENT_CHANGED_ERROR, System.currentTimeMillis()));
        requireLease(changed, task.getId());
    }

    /** 在当前租约内原子记录门禁事实；确定性阻断不进入网络投递重试。 */
    public void recordGateDecision(RuntimePublishTask task,
                                   String candidateVersionsJson,
                                   String evalRunIdsJson,
                                   String decisionJson,
                                   EvalGateStatus gateStatus,
                                   String failureSummary) {
        String publishStatus = gateStatus == EvalGateStatus.BLOCKED
            ? RuntimePublishStatus.BLOCKED.name() : RuntimePublishStatus.PROCESSING.name();
        String gateError = gateStatus == EvalGateStatus.BLOCKED ? failureSummary : null;
        int changed = CrossTenantOperations.execute(() -> taskMapper.recordGateDecision(
            task.getId(), workerId, candidateVersionsJson, evalRunIdsJson, decisionJson,
            gateStatus.name(), publishStatus, gateError, System.currentTimeMillis()));
        requireLease(changed, task.getId());
        task.setGateStatus(gateStatus.name());
        task.setStatus(publishStatus);
    }

    public void retryGateBlocked(String taskId, String tenantId) {
        int changed = CrossTenantOperations.execute(() ->
            taskMapper.retryGateBlocked(taskId, tenantId, System.currentTimeMillis()));
        if (changed != 1) {
            throw new IllegalStateException(
                "runtime publish task is not gate-blocked or has been superseded: " + taskId);
        }
    }

    public void overrideGateBlocked(String taskId, String tenantId, Long overrideId) {
        int changed = CrossTenantOperations.execute(() -> taskMapper.overrideGateBlocked(
            taskId, tenantId, overrideId, System.currentTimeMillis()));
        if (changed != 1) {
            throw new IllegalStateException(
                "runtime publish task is not gate-blocked or has been superseded: " + taskId);
        }
    }

    /** ACK 实例凭据已在入口绑定租户与 instanceId；此处核对正文身份、revision 与 hash。 */
    @Transactional(rollbackFor = Exception.class)
    public RuntimePublishStatus recordAck(RuntimeConfigAck ack, String authenticatedInstanceId) {
        validateAck(ack, authenticatedInstanceId);
        String tenantId = currentTenant();
        RuntimePublishTask task = taskMapper.lockByRevisionForAck(tenantId, ack.revision());
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
        int changed = taskMapper.updateAckStatus(
            tenantId, revision, aggregate.name(), System.currentTimeMillis());
        if (changed == 0) {
            RuntimePublishTask current = taskMapper.findByRevision(tenantId, revision);
            if (current != null && StringUtils.hasText(current.getStatus())) {
                return RuntimePublishStatus.valueOf(current.getStatus());
            }
        }
        return aggregate;
    }

    private void validateAck(RuntimeConfigAck ack, String authenticatedInstanceId) {
        if (ack == null || !StringUtils.hasText(ack.revision()) || !StringUtils.hasText(ack.contentHash())
            || !StringUtils.hasText(ack.instanceId()) || !StringUtils.hasText(authenticatedInstanceId)
            || ack.appliedAtMs() <= 0L) {
            throw new IllegalArgumentException(
                "revision, contentHash, instanceId and positive appliedAtMs are required");
        }
        if (!Objects.equals(ack.instanceId(), authenticatedInstanceId)) {
            throw new IllegalArgumentException("runtime config ACK instance identity mismatch");
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

    /** 入队时固化实际外写 dataId，claim 才能按 Nacos 真实键严格串行。 */
    private String resolveTaskDataId(String tenantId) {
        String baseDataId = properties.getNacos().getDataId();
        if (!tenantProperties.isEnabled()) {
            return baseDataId;
        }
        if (!TenantContext.isValidTenantId(tenantId)) {
            throw new IllegalArgumentException("runtime publish task tenant format is invalid");
        }
        return baseDataId + "-tenant-" + TenantContext.canonicalizeTenantId(tenantId);
    }

    /** group 也是 Nacos 键的一部分，必须与 dataId 一样在入队时固化。 */
    private String resolveTaskGroupName() {
        String groupName = properties.getNacos().getGroup();
        if (!StringUtils.hasText(groupName)) {
            throw new IllegalStateException("runtime publish nacos group is missing");
        }
        return groupName;
    }

    private void requireLease(int changed, String taskId) {
        if (changed != 1) {
            throw new RuntimePublishLeaseLostException(taskId);
        }
    }

    private void validateSafeCommand(SafePublishCommand command) {
        Objects.requireNonNull(command, "command");
        if (!StringUtils.hasText(command.operationId())
            || command.publishIntent() == null || !command.publishIntent().requiresRollbackPatch()
            || command.sourceConfigVersionId() == null
            || !StringUtils.hasText(command.sourceContentHash())
            || !command.sourceContentHash().matches("^[0-9a-fA-F]{64}$")
            || !StringUtils.hasText(command.rollbackPatchJson())
            || CollectionUtils.isEmpty(command.targets())) {
            throw new IllegalArgumentException("safe runtime publish command is incomplete");
        }
        if (command.publishIntent() == RuntimePublishIntent.SAFE_GRAY
            && !StringUtils.hasText(command.grayTenantsJson())) {
            throw new IllegalArgumentException("safe gray publish tenants are missing");
        }
        rollbackPatchExtractor.deserialize(command.rollbackPatchJson());
        for (SafePublishTarget target : command.targets()) {
            if (target == null || !StringUtils.hasText(target.tenantId()) || target.agentId() == null) {
                throw new IllegalArgumentException("safe runtime publish target is incomplete");
            }
        }
    }

    /** 单个目标租户已经过当前权威配置预校验的 Agent。 */
    public record SafePublishTarget(String tenantId, Long agentId) {
    }

    /** 整批安全回滚/灰度的不可变入队命令。 */
    public record SafePublishCommand(String operationId,
                                     RuntimePublishIntent publishIntent,
                                     Long sourceConfigVersionId,
                                     String sourceContentHash,
                                     String rollbackPatchJson,
                                     Integer sourceVersion,
                                     String remark,
                                     String grayTenantsJson,
                                     List<SafePublishTarget> targets) {
    }
}
