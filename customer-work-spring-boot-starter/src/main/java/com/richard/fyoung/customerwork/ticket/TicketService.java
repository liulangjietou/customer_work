package com.richard.fyoung.customerwork.ticket;

import com.richard.fyoung.customerwork.common.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工单业务服务（应用层入口）：把 {@link Ticket} 充血实体的状态机流转编排成"校验→流转→持久化→
 * 追加事件→广播监听器"的统一闭环。
 *
 * <p>每次成功流转都追加一条 {@link TicketEvent} 审计事件并广播给所有 {@link TicketEventListener}
 * （监听器异常只 error 日志、不中断主流程，也不影响其余监听器）。存储委托 {@link TicketStore} SPI：
 * 默认内存、生产切 JDBC。抢单走存储层 {@code claimAtomically} 条件更新，避免并发重复接单。</p>
 * @author owlzhangfq@gmail.com
 */
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    private static final String ID_PREFIX = "TK-";

    private final TicketStore store;
    /** 工单事件监听器集合（可为空）：每次流转后广播。 */
    private final ObjectProvider<TicketEventListener> listenerProvider;

    public TicketService(TicketStore store, ObjectProvider<TicketEventListener> listenerProvider) {
        this.store = store;
        this.listenerProvider = listenerProvider;
    }

    /**
     * 为会话建单（幂等）：若该会话已有活跃工单直接返回，避免同一会话重复开单。
     * 新单初始 AI_SERVING，工单号 {@code TK-<uuid>}。
     */
    public Ticket createForSession(String sessionId, String userId, String title, TicketCategory category) {
        Optional<Ticket> active = store.findActiveBySession(sessionId);
        if (active.isPresent()) {
            return active.get();
        }
        String id = ID_PREFIX + UUID.randomUUID();
        Ticket ticket = Ticket.create(id, sessionId, userId, title, category);
        store.save(ticket);
        TicketEvent event = store.appendEvent(TicketEvent.of(id, TicketEventType.CREATE,
            null, ticket.getStatus(), TicketActorType.SYSTEM, userId, title));
        broadcast(ticket, event);
        log.info("ticket created: id={}, session={}, user={}", id, sessionId, userId);
        return ticket;
    }

    /**
     * 请求转人工：按会话定位活跃工单（找不到 fast-fail），推进 AI_SERVING → WAITING_AGENT。
     * 仅当发生真实流转（实体返回 true）时才追加事件并广播——已在人工链路的幂等空转不重复发事件。
     */
    public Ticket requestHandoff(String sessionId, String reason, TicketActorType actorType, String actorId) {
        Ticket ticket = store.findActiveBySession(sessionId)
            .orElseThrow(() -> new IllegalStateException("no active ticket for session: " + sessionId));
        TicketStatus from = ticket.getStatus();
        boolean flowed = ticket.requestHandoff(reason);
        if (flowed) {
            store.update(ticket);
            TicketEvent event = store.appendEvent(TicketEvent.of(ticket.getId(),
                TicketEventType.REQUEST_HANDOFF, from, ticket.getStatus(), actorType, actorId, reason));
            broadcast(ticket, event);
            log.info("ticket handoff requested: id={}, reason={}", ticket.getId(), reason);
        }
        return ticket;
    }

    /**
     * 坐席接单：走存储层原子抢单（条件更新），失败（已被抢 / 非 WAITING_AGENT）fast-fail，
     * 语义等价 HTTP 409 Conflict。成功后追加 CLAIM 事件并广播。
     */
    public Ticket claim(String ticketId, String agentId) {
        boolean claimed = store.claimAtomically(ticketId, agentId, System.currentTimeMillis());
        if (!claimed) {
            throw new IllegalStateException("ticket already claimed or not waiting: " + ticketId);
        }
        Ticket ticket = require(ticketId);
        TicketEvent event = store.appendEvent(TicketEvent.of(ticketId, TicketEventType.CLAIM,
            TicketStatus.WAITING_AGENT, TicketStatus.PROCESSING, TicketActorType.AGENT, agentId, null));
        broadcast(ticket, event);
        log.info("ticket claimed: id={}, agent={}", ticketId, agentId);
        return ticket;
    }

    /** 撤销转人工：WAITING_AGENT → AI_SERVING。 */
    public Ticket cancelHandoff(String ticketId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, Ticket::cancelHandoff, TicketEventType.CANCEL_HANDOFF,
            actorType, actorId, null);
    }

    /** 挂起：PROCESSING → ON_HOLD。 */
    public Ticket hold(String ticketId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, Ticket::hold, TicketEventType.HOLD, actorType, actorId, null);
    }

    /** 恢复处理：ON_HOLD → PROCESSING。 */
    public Ticket resume(String ticketId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, Ticket::resume, TicketEventType.RESUME, actorType, actorId, null);
    }

    /** 转回队列：PROCESSING|ON_HOLD → WAITING_AGENT（清坐席）。 */
    public Ticket transferToPool(String ticketId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, Ticket::transferToPool, TicketEventType.TRANSFER,
            actorType, actorId, null);
    }

    /** 转派其他坐席：PROCESSING 态换绑坐席。 */
    public Ticket transferToAgent(String ticketId, String newAgentId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.transferToAgent(newAgentId), TicketEventType.TRANSFER,
            actorType, actorId, newAgentId);
    }

    /** 标记处理完毕：PROCESSING → WAITING_CONFIRM。 */
    public Ticket markResolved(String ticketId, String note, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.markResolved(note), TicketEventType.MARK_RESOLVED,
            actorType, actorId, note);
    }

    /** 用户确认：WAITING_CONFIRM → RESOLVED。 */
    public Ticket confirm(String ticketId, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, Ticket::confirm, TicketEventType.CONFIRM, actorType, actorId, null);
    }

    /** 用户驳回：WAITING_CONFIRM → PROCESSING。 */
    public Ticket reject(String ticketId, String reason, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.reject(reason), TicketEventType.REJECT,
            actorType, actorId, reason);
    }

    /** 关闭工单：AI_SERVING|RESOLVED|WAITING_CONFIRM → CLOSED。 */
    public Ticket close(String ticketId, String reason, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.close(reason), TicketEventType.CLOSE,
            actorType, actorId, reason);
    }

    /**
     * 强制关闭：任意非 CLOSED 态直达 CLOSED（空闲超时自动结束 / 用户强制结束用）。
     * 走统一闭环追加 FORCE_CLOSE 事件并广播（下游 WS 监听器据此实时推关闭事件给前端）。
     */
    public Ticket forceClose(String ticketId, String reason, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.forceClose(reason), TicketEventType.FORCE_CLOSE,
            actorType, actorId, reason);
    }

    /** 重新打开：RESOLVED|CLOSED → WAITING_AGENT。 */
    public Ticket reopen(String ticketId, String reason, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.reopen(reason), TicketEventType.REOPEN,
            actorType, actorId, reason);
    }

    /** 变更优先级（任意非 CLOSED 态）。 */
    public Ticket changePriority(String ticketId, TicketPriority priority, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.changePriority(priority), TicketEventType.PRIORITY_CHANGE,
            actorType, actorId, priority == null ? null : priority.name());
    }

    /** 变更分类（任意非 CLOSED 态）。 */
    public Ticket changeCategory(String ticketId, TicketCategory category, TicketActorType actorType, String actorId) {
        return applyTransition(ticketId, t -> t.changeCategory(category), TicketEventType.CATEGORY_CHANGE,
            actorType, actorId, category == null ? null : category.name());
    }

    /**
     * 回填工单标题（仅当原标题为空白时生效）：成功回填才持久化，且<b>不发事件</b>——标题补白不是
     * 状态流转，无需进审计轨迹，也不必广播给坐席工作台。
     *
     * @return true 表示发生了回填并已持久化；false 表示工单已有标题或给定标题为空白
     */
    public boolean fillTitle(String ticketId, String title) {
        Ticket ticket = require(ticketId);
        boolean filled = ticket.fillTitleIfBlank(title);
        if (filled) {
            store.update(ticket);
            log.info("ticket title filled: id={}", ticketId);
        }
        return filled;
    }

    /**
     * 刷新会话活跃工单的用户最后活跃时间（用户每发一条消息时调用）：仅当该会话有活跃工单时生效，
     * <b>不发事件、不广播</b>——刷新活跃时间不是状态流转（与 {@link #fillTitle} 同属非流转持久化）。
     *
     * @return true 表示命中活跃工单并已刷新；false 表示会话无活跃工单（无需刷新）
     */
    public boolean touchUserActive(String sessionId) {
        Optional<Ticket> active = store.findActiveBySession(sessionId);
        if (active.isEmpty()) {
            return false;
        }
        Ticket ticket = active.get();
        ticket.markUserActive();
        store.update(ticket);
        return true;
    }

    // ---- 查询透传 ----

    public Optional<Ticket> find(String ticketId) {
        return store.find(ticketId);
    }

    public Optional<Ticket> findActiveBySession(String sessionId) {
        return store.findActiveBySession(sessionId);
    }

    /** 查该用户的活跃工单（非 CLOSED/RESOLVED 的最新一张，用于用户级唯一活跃会话去重）。 */
    public Optional<Ticket> findActiveByUser(String userId) {
        return store.findActiveByUser(userId);
    }

    public PageResult<Ticket> findPage(TicketQuery query) {
        return store.findPage(query);
    }

    /** 按状态查全部（SLA 巡检等批量扫描用）。 */
    public List<Ticket> findByStatus(TicketStatus status) {
        return store.findByStatus(status);
    }

    public List<TicketEvent> findEvents(String ticketId) {
        return store.findEvents(ticketId);
    }

    /** 统一流转闭环：定位工单 → 执行实体状态机方法 → 持久化 → 追加事件 → 广播。 */
    private Ticket applyTransition(String ticketId, Consumer<Ticket> op, TicketEventType type,
                                  TicketActorType actorType, String actorId, String note) {
        Ticket ticket = require(ticketId);
        TicketStatus from = ticket.getStatus();
        op.accept(ticket);
        store.update(ticket);
        TicketEvent event = store.appendEvent(TicketEvent.of(ticketId, type, from, ticket.getStatus(),
            actorType, actorId, note));
        broadcast(ticket, event);
        log.info("ticket transition: id={}, type={}, from={}, to={}", ticketId, type, from, ticket.getStatus());
        return ticket;
    }

    /** 单一防御点：工单必须存在，否则 fast-fail。 */
    private Ticket require(String ticketId) {
        return store.find(ticketId)
            .orElseThrow(() -> new NoSuchElementException("ticket not found: " + ticketId));
    }

    /** 广播事件给全部监听器；单个监听器异常只 error 日志，不中断主流程与其余监听器。 */
    private void broadcast(Ticket ticket, TicketEvent event) {
        if (listenerProvider == null) {
            return;
        }
        listenerProvider.forEach(listener -> {
            try {
                listener.onTicketEvent(ticket, event);
            } catch (Exception e) {
                log.error("ticket listener failed, code={}, id={}, listener={}",
                    "TICKET-LISTENER-FAIL", ticket.getId(), listener.getClass().getSimpleName(), e);
            }
        });
    }
}
