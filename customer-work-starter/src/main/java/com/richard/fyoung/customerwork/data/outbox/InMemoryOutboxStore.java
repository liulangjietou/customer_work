package com.richard.fyoung.customerwork.data.outbox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Outbox 内存实现，仅用于测试与离线模式。 */
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxMessage> messages = new ConcurrentHashMap<>();
    private final Map<String, String> leaseOwners = new ConcurrentHashMap<>();

    @Override
    public void save(OutboxMessage message) {
        messages.put(message.getId(), message);
    }

    @Override
    public synchronized List<OutboxMessage> claimDue(String owner, long nowMs, long leaseUntilMs, int limit) {
        List<OutboxMessage> due = messages.values().stream()
            .filter(message -> (message.getStatus() == OutboxStatus.PENDING
                && message.getNextAttemptAtMs() <= nowMs)
                || (message.getStatus() == OutboxStatus.PROCESSING && message.getLeaseUntilMs() <= nowMs))
            .sorted(Comparator.comparingLong(OutboxMessage::getNextAttemptAtMs))
            .limit(Math.max(limit, 0))
            .toList();
        List<OutboxMessage> claimed = new ArrayList<>(due.size());
        for (OutboxMessage message : due) {
            message.restore(OutboxStatus.PROCESSING, message.getAttempts(), message.getNextAttemptAtMs(),
                owner, leaseUntilMs, message.getLastError(), message.getFinishedAtMs());
            leaseOwners.put(message.getId(), owner);
            claimed.add(message);
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized boolean complete(OutboxMessage message, String owner) {
        if (!owner.equals(leaseOwners.get(message.getId()))) {
            return false;
        }
        leaseOwners.remove(message.getId());
        messages.put(message.getId(), message);
        return true;
    }

    @Override
    public long count(OutboxStatus status) {
        return messages.values().stream().filter(message -> message.getStatus() == status).count();
    }
}
