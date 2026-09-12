package com.richard.fyoung.customerwork.capability.approval;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内审批工单存储（默认实现，从 {@link PendingApprovalService} 抽取的存储逻辑）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全；{@link #update} 在内存实现中与 {@link #save}
 * 等价（同一引用覆盖），但保留独立方法签名以便 JDBC 等实现区分 INSERT 与 UPDATE 语义。</p>
 *
 * <p>以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link ApprovalStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryApprovalStore implements ApprovalStore {

    private final ConcurrentHashMap<String, TenantApprovals> storesByTenant = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        Map<String, ApprovalRequest> store = currentStore();
        if (request == null || request.getId() == null) {
            return;
        }
        store.put(request.getId(), request);
    }

    @Override
    public Optional<ApprovalRequest> find(String id) {
        return Optional.ofNullable(currentStore().get(id));
    }

    @Override
    public List<ApprovalRequest> findAll() {
        return new ArrayList<>(currentStore().values());
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        return currentStore().values().stream()
            .filter(r -> r.getStatus() == status)
            .collect(Collectors.toList());
    }

    @Override
    public void update(ApprovalRequest request) {
        save(request);  // 内存实现：upsert
    }

    @Override
    public synchronized boolean decide(String id, ApprovalStatus target, String operator,
                                       String note, long decidedAtMs) {
        ApprovalRequest request = currentStore().get(id);
        if (request == null || request.getStatus() != ApprovalStatus.PENDING) {
            return false;
        }
        if (target == ApprovalStatus.APPROVED) {
            request.approve(operator, note, decidedAtMs);
        } else if (target == ApprovalStatus.DENIED) {
            request.deny(operator, note, decidedAtMs);
        } else {
            throw new IllegalArgumentException("unsupported approval decision: " + target);
        }
        return true;
    }

    @Override
    public synchronized boolean claimExecution(String id, int maxAttempts,
                                               long startedAtMs, String fencingToken) {
        ApprovalRequest request = currentStore().get(id);
        if (request == null || request.getStatus() != ApprovalStatus.APPROVED
            || request.getExecutionAttempts() >= maxAttempts
            || (request.getExecutionStatus() != ExecutionStatus.NOT_APPLICABLE
                && request.getExecutionStatus() != ExecutionStatus.EXECUTE_FAILED)) {
            return false;
        }
        request.markExecuting(startedAtMs, fencingToken);
        return true;
    }

    @Override
    public synchronized boolean completeExecution(String id, String fencingToken,
                                                  boolean success, String failureReason) {
        ApprovalRequest request = currentStore().get(id);
        if (request == null || request.getExecutionStatus() != ExecutionStatus.EXECUTING
            || request.getExecutionFailureReason() == null
            || !request.getExecutionFailureReason().endsWith(":" + fencingToken)) {
            return false;
        }
        if (success) {
            request.markExecuted();
        } else {
            request.markExecutionFailed(failureReason);
        }
        return true;
    }

    @Override
    public synchronized int recoverStuckExecutions(long startedBeforeMs) {
        int recovered = 0;
        for (ApprovalRequest request : currentStore().values()) {
            if (request.getExecutionStatus() == ExecutionStatus.EXECUTING
                && executionStartedAt(request) < startedBeforeMs) {
                request.markExecutionFailed("execution lease expired");
                recovered++;
            }
        }
        return recovered;
    }

    private long executionStartedAt(ApprovalRequest request) {
        try {
            String[] marker = request.getExecutionFailureReason().split(":", 3);
            return Long.parseLong(marker[1]);
        } catch (Exception ignored) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void delete(String id) {
        currentStore().remove(id);
    }

    /** 当前租户是读写的必要身份；内存键与数据库使用相同的租户大小写语义。 */
    private Map<String, ApprovalRequest> currentStore() {
        String tenantId = TenantContext.require();
        String tenantKey = TenantContext.normalizedTenantKey(tenantId);
        return storesByTenant.computeIfAbsent(tenantKey,
            ignored -> new TenantApprovals(tenantId, new ConcurrentHashMap<>())).requests();
    }

    /** 仅供同领域定时器恢复已经建立的租户分区，不对外暴露全局审批读取。 */
    List<String> tenantIds() {
        return storesByTenant.values().stream().map(TenantApprovals::tenantId).toList();
    }

    /** 归一键只用于内部索引；定时执行仍恢复原始业务租户，避免改变外部资源命名空间。 */
    private record TenantApprovals(String tenantId, Map<String, ApprovalRequest> requests) { }
}
