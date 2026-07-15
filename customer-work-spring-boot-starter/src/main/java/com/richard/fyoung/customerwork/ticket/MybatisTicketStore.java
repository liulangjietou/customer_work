package com.richard.fyoung.customerwork.ticket;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customerwork.common.PageResult;
import com.richard.fyoung.customerwork.ticket.entity.TicketDO;
import com.richard.fyoung.customerwork.ticket.entity.TicketEventDO;
import com.richard.fyoung.customerwork.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.ticket.mapper.TicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 工单存储（生产参考实现：{@code customer-work.ticket.store-mode=jdbc} 时启用）。
 *
 * <p>把工单主表 {@code cw_ticket} 与事件轨迹表 {@code cw_ticket_event} 结构化落库，保证重启 / 多实例
 * 部署下工单与操作轨迹不丢失。{@link #claimAtomically} 用条件 UPDATE 的影响行数判定并发抢单——
 * 只有一名坐席能把 WAITING_AGENT 置为 PROCESSING，跨实例并发安全（进程内 {@link InMemoryTicketStore}
 * 靠单机 synchronized，多实例无此保证）。建表与种子由统一的 SchemaInitializer 负责，本类不建表。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisTicketStore implements TicketStore {

    private static final Logger log = LoggerFactory.getLogger(MybatisTicketStore.class);

    private final TicketMapper ticketMapper;
    private final TicketEventMapper ticketEventMapper;

    public MybatisTicketStore(TicketMapper ticketMapper, TicketEventMapper ticketEventMapper) {
        this.ticketMapper = ticketMapper;
        this.ticketEventMapper = ticketEventMapper;
    }

    @Override
    public void save(Ticket ticket) {
        if (ticket == null || ticket.getId() == null) {
            return;
        }
        try {
            ticketMapper.upsert(toDO(ticket));
        } catch (Exception e) {
            log.error("ticket save failed, code={}, id={}", "TICKET-STORE-SAVE-FAIL", ticket.getId(), e);
            throw new IllegalStateException("failed to save ticket: " + ticket.getId(), e);
        }
    }

    @Override
    public void update(Ticket ticket) {
        save(ticket);
    }

    @Override
    public Optional<Ticket> find(String id) {
        try {
            TicketDO record = ticketMapper.selectById(id);
            return record == null ? Optional.empty() : Optional.of(toTicket(record));
        } catch (Exception e) {
            log.error("ticket find failed, code={}, id={}", "TICKET-STORE-FIND-FAIL", id, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Ticket> findActiveBySession(String sessionId) {
        try {
            TicketDO record = ticketMapper.findActiveBySession(sessionId);
            return record == null ? Optional.empty() : Optional.of(toTicket(record));
        } catch (Exception e) {
            log.error("ticket findActiveBySession failed, code={}, session={}",
                "TICKET-STORE-FINDACTIVE-FAIL", sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public PageResult<Ticket> findPage(TicketQuery query) {
        try {
            Page<TicketDO> page = Page.of(query.normalizedPageNum(), query.normalizedPageSize());
            QueryWrapper<TicketDO> wrapper = new QueryWrapper<>();
            if (query.status() != null) {
                wrapper.eq("status", query.status().name());
            }
            if (query.assignee() != null) {
                wrapper.eq("assignee", query.assignee());
            }
            if (query.category() != null) {
                wrapper.eq("category", query.category().name());
            }
            if (query.priority() != null) {
                wrapper.eq("priority", query.priority().name());
            }
            if (query.userId() != null) {
                wrapper.eq("user_id", query.userId());
            }
            wrapper.orderByDesc("updated_at_ms");
            ticketMapper.selectPage(page, wrapper);
            List<Ticket> list = page.getRecords().stream().map(this::toTicket).collect(Collectors.toList());
            return new PageResult<>(page.getTotal(), list);
        } catch (Exception e) {
            log.error("ticket findPage failed, code={}", "TICKET-STORE-FINDPAGE-FAIL", e);
            return new PageResult<>(0, List.of());
        }
    }

    @Override
    public List<Ticket> findByStatus(TicketStatus status) {
        try {
            QueryWrapper<TicketDO> wrapper = new QueryWrapper<TicketDO>().eq("status", status.name());
            return ticketMapper.selectList(wrapper).stream().map(this::toTicket).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("ticket findByStatus failed, code={}, status={}",
                "TICKET-STORE-FINDBYSTATUS-FAIL", status, e);
            return List.of();
        }
    }

    @Override
    public TicketEvent appendEvent(TicketEvent event) {
        try {
            TicketEventDO record = toEventDO(event);
            ticketEventMapper.insert(record);
            return event.withId(record.getId());
        } catch (Exception e) {
            log.error("ticket appendEvent failed, code={}, ticketId={}",
                "TICKET-STORE-APPENDEVENT-FAIL", event.ticketId(), e);
            throw new IllegalStateException("failed to append ticket event: " + event.ticketId(), e);
        }
    }

    @Override
    public List<TicketEvent> findEvents(String ticketId) {
        try {
            QueryWrapper<TicketEventDO> wrapper = new QueryWrapper<TicketEventDO>()
                .eq("ticket_id", ticketId).orderByAsc("id");
            return ticketEventMapper.selectList(wrapper).stream().map(this::toEvent).collect(Collectors.toList());
        } catch (Exception e) {
            log.error("ticket findEvents failed, code={}, ticketId={}",
                "TICKET-STORE-FINDEVENTS-FAIL", ticketId, e);
            return List.of();
        }
    }

    @Override
    public boolean claimAtomically(String id, String agentId, long nowMs) {
        try {
            return ticketMapper.claimAtomically(id, agentId, nowMs) > 0;
        } catch (Exception e) {
            log.error("ticket claimAtomically failed, code={}, id={}", "TICKET-CLAIM-FAIL", id, e);
            return false;
        }
    }

    /** 领域对象 → 主表 DO（枚举取 name()）。 */
    private TicketDO toDO(Ticket t) {
        TicketDO record = new TicketDO();
        record.setId(t.getId());
        record.setSessionId(t.getSessionId());
        record.setUserId(t.getUserId());
        record.setTitle(t.getTitle());
        record.setCategory(t.getCategory() == null ? null : t.getCategory().name());
        record.setPriority(t.getPriority() == null ? null : t.getPriority().name());
        record.setStatus(t.getStatus().name());
        record.setAssignee(t.getAssignee());
        record.setHandoffReason(t.getHandoffReason());
        record.setResolveNote(t.getResolveNote());
        record.setReopenCount(t.getReopenCount());
        record.setCreatedAtMs(t.getCreatedAtMs());
        record.setUpdatedAtMs(t.getUpdatedAtMs());
        record.setHandoffAtMs(t.getHandoffAtMs());
        record.setClaimedAtMs(t.getClaimedAtMs());
        record.setResolvedAtMs(t.getResolvedAtMs());
        record.setClosedAtMs(t.getClosedAtMs());
        return record;
    }

    /** 主表 DO → 领域对象（跳过状态机校验，走 reconstruct）。 */
    private Ticket toTicket(TicketDO record) {
        return Ticket.reconstruct(
            record.getId(),
            record.getSessionId(),
            record.getUserId(),
            record.getTitle(),
            enumOrNull(TicketCategory.class, record.getCategory()),
            enumOrNull(TicketPriority.class, record.getPriority()),
            TicketStatus.valueOf(record.getStatus()),
            record.getAssignee(),
            record.getHandoffReason(),
            record.getResolveNote(),
            record.getReopenCount() == null ? 0 : record.getReopenCount(),
            orZero(record.getCreatedAtMs()),
            orZero(record.getUpdatedAtMs()),
            orZero(record.getHandoffAtMs()),
            orZero(record.getClaimedAtMs()),
            orZero(record.getResolvedAtMs()),
            orZero(record.getClosedAtMs()));
    }

    /** 事件领域对象 → 事件 DO（id 交由自增回填，此处不设）。 */
    private TicketEventDO toEventDO(TicketEvent event) {
        TicketEventDO record = new TicketEventDO();
        record.setTicketId(event.ticketId());
        record.setEventType(event.eventType().name());
        record.setFromStatus(event.fromStatus() == null ? null : event.fromStatus().name());
        record.setToStatus(event.toStatus() == null ? null : event.toStatus().name());
        record.setActorType(event.actorType().name());
        record.setActorId(event.actorId());
        record.setNote(event.note());
        record.setCreatedAtMs(event.createdAtMs());
        return record;
    }

    /** 事件 DO → 事件领域对象。 */
    private TicketEvent toEvent(TicketEventDO record) {
        return new TicketEvent(
            record.getId(),
            record.getTicketId(),
            TicketEventType.valueOf(record.getEventType()),
            enumOrNull(TicketStatus.class, record.getFromStatus()),
            enumOrNull(TicketStatus.class, record.getToStatus()),
            TicketActorType.valueOf(record.getActorType()),
            record.getActorId(),
            record.getNote(),
            orZero(record.getCreatedAtMs()));
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
