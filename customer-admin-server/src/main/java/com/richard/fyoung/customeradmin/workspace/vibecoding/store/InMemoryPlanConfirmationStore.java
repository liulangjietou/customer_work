package com.richard.fyoung.customeradmin.workspace.vibecoding.store;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 单测/嵌入式使用；多个服务实例共享同一个对象即可模拟跨 Pod 共享存储。 */
public class InMemoryPlanConfirmationStore implements PlanConfirmationStore {

    private final ConcurrentMap<Key, PlanConfirmationRecord> records = new ConcurrentHashMap<>();

    @Override
    public boolean create(PlanConfirmationRecord record) {
        return records.putIfAbsent(Key.from(record), record) == null;
    }

    @Override
    public Optional<PlanConfirmationRecord> find(String tenantId, String agentCode, String sessionId, String planId) {
        return Optional.ofNullable(records.get(new Key(tenantId, agentCode, sessionId, planId)));
    }

    @Override
    public boolean transition(String tenantId, String agentCode, String sessionId, String planId,
                              PlanConfirmationState target) {
        AtomicBoolean changed = new AtomicBoolean(false);
        Key key = new Key(tenantId, agentCode, sessionId, planId);
        records.computeIfPresent(key, (ignored, current) -> {
            if (current.state() != PlanConfirmationState.PENDING || expired(current, target)) {
                return current;
            }
            changed.set(true);
            return new PlanConfirmationRecord(current.tenantId(), current.agentCode(), current.sessionId(),
                current.planId(), target, current.expireAt());
        });
        return changed.get();
    }

    private boolean expired(PlanConfirmationRecord current, PlanConfirmationState target) {
        return (target == PlanConfirmationState.APPROVED || target == PlanConfirmationState.REJECTED)
            && current.expireAt() != null && !LocalDateTime.now().isBefore(current.expireAt());
    }

    private record Key(String tenantId, String agentCode, String sessionId, String planId) {
        private static Key from(PlanConfirmationRecord record) {
            return new Key(record.tenantId(), record.agentCode(), record.sessionId(), record.planId());
        }
    }
}
