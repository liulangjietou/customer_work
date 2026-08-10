package com.richard.fyoung.customerwork.capability.handoff;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内人机切换工单存储（默认实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 保证线程安全；{@link #update} 在内存实现中与 {@link #save}
 * 等价（同一引用覆盖），但保留独立方法签名以便 JDBC 等实现区分 INSERT 与 UPDATE 语义。</p>
 *
 * <p>以 {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link HandoffStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryHandoffStore implements HandoffStore {

    private final ConcurrentHashMap<String, HandoffTicket> store = new ConcurrentHashMap<>();

    @Override
    public void save(HandoffTicket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        store.put(ticket.getId(), ticket);
    }

    @Override
    public Optional<HandoffTicket> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<HandoffTicket> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<HandoffTicket> findByStatus(HandoffStatus status) {
        return store.values().stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
    }

    @Override
    public void update(HandoffTicket ticket) {
        save(ticket);  // 内存实现：upsert
    }
}
