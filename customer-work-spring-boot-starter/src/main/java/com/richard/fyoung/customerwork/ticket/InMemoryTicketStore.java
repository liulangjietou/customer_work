package com.richard.fyoung.customerwork.ticket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 进程内工单存储（默认实现，测试 / 演示用）。
 *
 * <p>{@link ConcurrentHashMap} 保证线程安全；事件自增主键用 {@link AtomicLong} 模拟。{@code claimAtomically}
 * 复用 {@link TicketStore} 接口默认的 {@code synchronized} 实现（并发抢单同语义）。以
 * {@code @ConditionalOnMissingBean} 注册，下游声明自己的 {@link TicketStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
public class InMemoryTicketStore implements TicketStore {

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TicketEvent>> events = new ConcurrentHashMap<>();
    private final AtomicLong eventIdSeq = new AtomicLong(0);

    @Override
    public void save(Ticket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        tickets.put(ticket.getId(), ticket);
    }

    @Override
    public void update(Ticket ticket) {
        save(ticket);
    }

    @Override
    public Optional<Ticket> find(String id) {
        return Optional.ofNullable(tickets.get(id));
    }

    @Override
    public Optional<Ticket> findActiveBySession(String sessionId) {
        return tickets.values().stream()
            .filter(t -> t.getSessionId() != null && t.getSessionId().equals(sessionId))
            .filter(InMemoryTicketStore::isActive)
            .max(Comparator.comparingLong(Ticket::getCreatedAtMs));
    }

    @Override
    public PageResult findPage(TicketQuery query) {
        List<Ticket> filtered = tickets.values().stream()
            .filter(t -> query.status() == null || t.getStatus() == query.status())
            .filter(t -> query.assignee() == null || query.assignee().equals(t.getAssignee()))
            .filter(t -> query.category() == null || t.getCategory() == query.category())
            .filter(t -> query.priority() == null || t.getPriority() == query.priority())
            .filter(t -> query.userId() == null || query.userId().equals(t.getUserId()))
            .sorted(Comparator.comparingLong(Ticket::getUpdatedAtMs).reversed())
            .collect(Collectors.toList());

        long total = filtered.size();
        int from = Math.min(query.offset(), filtered.size());
        int to = Math.min(from + query.normalizedPageSize(), filtered.size());
        return new PageResult(total, new ArrayList<>(filtered.subList(from, to)));
    }

    @Override
    public List<Ticket> findByStatus(TicketStatus status) {
        return tickets.values().stream()
            .filter(t -> t.getStatus() == status)
            .collect(Collectors.toList());
    }

    @Override
    public TicketEvent appendEvent(TicketEvent event) {
        TicketEvent persisted = event.withId(eventIdSeq.incrementAndGet());
        List<TicketEvent> list = events.computeIfAbsent(event.ticketId(), k -> new ArrayList<>());
        // ArrayList 非线程安全，追加时按 ticketId 维度加锁（同一工单事件多为顺序写，锁竞争极低）
        synchronized (list) {
            list.add(persisted);
        }
        return persisted;
    }

    @Override
    public List<TicketEvent> findEvents(String ticketId) {
        List<TicketEvent> list = events.get(ticketId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
            .sorted(Comparator.comparingLong(TicketEvent::id))
            .collect(Collectors.toList());
    }

    /** 活跃 = 非 CLOSED 且非 RESOLVED（仍在服务生命周期内的工单）。 */
    private static boolean isActive(Ticket ticket) {
        return ticket.getStatus() != TicketStatus.CLOSED && ticket.getStatus() != TicketStatus.RESOLVED;
    }
}
