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
    public void delete(String id) {
        store.remove(id);
    }
}
