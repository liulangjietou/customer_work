package com.richard.fyoung.customerwork.capability.approval;

import java.util.ArrayList;
import java.util.List;
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

    private final ConcurrentHashMap<String, ApprovalRequest> store = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        if (request == null || request.getId() == null) {
            return;
        }
        store.put(request.getId(), request);
    }

    @Override
    public Optional<ApprovalRequest> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ApprovalRequest> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        return store.values().stream()
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
        ApprovalRequest request = store.get(id);
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
        ApprovalRequest request = store.get(id);
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
        ApprovalRequest request = store.get(id);
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
        for (ApprovalRequest request : store.values()) {
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
        store.remove(id);
    }
}
