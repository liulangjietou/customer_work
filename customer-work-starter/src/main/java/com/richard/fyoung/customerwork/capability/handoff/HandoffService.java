package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.capability.routing.HandoffCreatedEnricher;
import com.richard.fyoung.customerwork.data.ticket.InMemoryTicketStore;
import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * 人机切换兼容服务（AI→人工接管→人工→AI 回收）。
 *
 * <p>取代 {@code HumanHandoffTools.transferToHuman} 此前"只打日志 + 生成随机字符串工单号"的空实现——
 * 转人工不再是一句无状态的话术，而是一张可查询、可流转的 {@link HandoffTicket}：AI 转出生成
 * {@code PENDING} 工单 → 坐席 {@link #claim} 接单（{@code CLAIMED}）→ 坐席处理完毕
 * {@link #resolve}（{@code RESOLVED}，会话可回收给 AI 续接）。</p>
 *
 * <p>生产环境只以 {@link TicketService}/{@code cw_ticket} 为权威状态机。本服务保留旧 /handoffs
 * 三态合同，将完整工单投影为 PENDING/CLAIMED/RESOLVED；所有写动作仍回到 TicketService，避免
 * {@code cw_handoff_ticket} 与 {@code cw_ticket} 双写分叉。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);

    private static final String ID_PREFIX = "HO-";
    private static final String LEGACY_USER_PREFIX = "legacy-handoff:";
    private static final String SYSTEM_OPERATOR = "handoff-api";

    private final TicketService ticketService;
    /** 仅供旧表迁移回归单测显式构造，Spring 生产路径永不注入。 */
    private final HandoffStore legacyStore;

    /**
     * 转人工增强器（智能路由中控·会话总结 + 工单智能分配）：<b>可选</b>，setter 注入。
     *
     * <p>用 setter 而非构造注入：一是保留既有无参/单参构造不破坏旧测试；二是打破
     * {@code HandoffService ⇆ HandoffCreatedEnricher}（增强器构造依赖本服务做推荐回写）的循环依赖。
     * 未装配（未开启增强 / 纯工具场景）时建单行为与此前完全一致。</p>
     */
    private HandoffCreatedEnricher enricher;

    /**
     * Spring 生产构造：完整 {@link TicketService} 是唯一权威工单服务。
     */
    @Autowired
    public HandoffService(TicketService ticketService) {
        this.ticketService = ticketService;
        this.legacyStore = null;
    }

    /** 可选注入转人工增强器（不存在则不增强，建单行为不变）。 */
    @Autowired(required = false)
    public void setEnricher(HandoffCreatedEnricher enricher) {
        this.enricher = enricher;
    }

    /** 无 Spring 场景仍使用完整 Ticket 状态机，而不是另起一套 Handoff 状态。 */
    public HandoffService() {
        this(new TicketService(new InMemoryTicketStore(), null));
    }

    /**
     * 旧表迁移回归适配器：仅供显式构造的存储单测使用，不是生产 Bean 构造路径。
     * 生产代码必须注入 TicketService。
     */
    public HandoffService(HandoffStore legacyStore) {
        this.ticketService = null;
        this.legacyStore = legacyStore;
    }

    /** AI 转出：在权威工单上推进到 WAITING_AGENT，并返回兼容三态读模型。 */
    public HandoffTicket create(String sessionId, String reason) {
        if (isLegacyMode()) {
            String id = ID_PREFIX + UUID.randomUUID();
            HandoffTicket ticket = new HandoffTicket(id, sessionId, reason, System.currentTimeMillis());
            legacyStore.save(ticket);
            fireEnrichment(ticket);
            return ticket;
        }
        Ticket ticket = ticketService.findActiveBySession(sessionId)
            .orElseGet(() -> ticketService.createForSession(sessionId, LEGACY_USER_PREFIX + sessionId,
                reason, TicketCategory.OTHER));
        TicketStatus before = ticket.getStatus();
        Ticket handedOff = ticketService.requestHandoff(sessionId, reason, TicketActorType.BOT, null);
        HandoffTicket projection = HandoffTicket.fromTicket(handedOff);
        if (before == TicketStatus.AI_SERVING) {
            fireEnrichment(projection);
        }
        log.info("handoff created on canonical ticket: id={}, session={}", handedOff.getId(), sessionId);
        return projection;
    }

    /**
     * 触发转人工增强（会话摘要预生成 + 工单分类打分推荐），<b>异步、fail-open</b>：增强器内部把耗时工作派发到
     * 独立线程池，此处仅同步派发。摘要/推荐是增强，挂了不能影响转人工——故即便派发本身异常也只 error 不抛。
     */
    private void fireEnrichment(HandoffTicket ticket) {
        if (enricher == null) {
            return;
        }
        try {
            enricher.onHandoffCreated(ticket);
        } catch (Exception e) {
            log.error("handoff enrichment trigger failed, code={}, id={}",
                "HANDOFF-ENRICH-TRIGGER-FAIL", ticket.getId(), e);
        }
    }

    /**
     * 回写工单智能分配的分类与推荐结果（由 {@code HandoffCreatedEnricher} 异步调用）。
     *
     * <p>生产回写同一张 {@code cw_ticket}。工单不存在或增强结果迟到终态时只 error 记录、不抛，
     * 因为路由建议是 fail-open 旁路，不能反向破坏已完成的转人工主链路。</p>
     */
    public void applyRoutingSuggestion(String id, String category, String requiredSkill,
                                       String priority, String emotion, String suggestedAssignees) {
        if (isLegacyMode()) {
            Optional<HandoffTicket> found = legacyStore.find(id);
            if (found.isEmpty()) {
                routingMiss(id);
                return;
            }
            HandoffTicket ticket = found.get();
            ticket.applyRoutingSuggestion(category, requiredSkill, priority, emotion, suggestedAssignees);
            legacyStore.update(ticket);
            return;
        }
        if (find(id).isEmpty()) {
            routingMiss(id);
            return;
        }
        try {
            ticketService.applyRoutingSuggestion(id, category, requiredSkill, priority, emotion,
                suggestedAssignees);
            log.info("handoff routing suggestion applied: id={}, category={}, priority={}",
                id, category, priority);
        } catch (IllegalStateException e) {
            log.error("handoff routing suggestion failed, code={}, id={}",
                "HANDOFF-ROUTING-APPLY-FAIL", id, e);
        }
    }

    private void routingMiss(String id) {
        log.error("handoff routing suggestion skipped, code={}, id={}",
            "HANDOFF-ROUTING-APPLY-MISS", id);
    }

    /** 全部工单（含已结案）。 */
    public List<HandoffTicket> list() {
        if (isLegacyMode()) {
            return legacyStore.findAll();
        }
        List<HandoffTicket> tickets = new ArrayList<>();
        for (TicketStatus status : TicketStatus.values()) {
            if (status == TicketStatus.AI_SERVING) {
                continue;
            }
            ticketService.findByStatus(status).stream()
                .filter(HandoffService::wasHandedOff)
                .map(HandoffTicket::fromTicket)
                .forEach(tickets::add);
        }
        tickets.sort(Comparator.comparingLong(HandoffTicket::getCreatedAtMs).reversed());
        return tickets;
    }

    /** 按状态过滤（如只看 PENDING 待接单）。 */
    public List<HandoffTicket> listByStatus(HandoffStatus status) {
        if (isLegacyMode()) {
            return legacyStore.findByStatus(status);
        }
        return list().stream().filter(ticket -> ticket.getStatus() == status).toList();
    }

    public Optional<HandoffTicket> find(String id) {
        if (isLegacyMode()) {
            return legacyStore.find(id);
        }
        return ticketService.find(id)
            .filter(HandoffService::wasHandedOff)
            .filter(ticket -> ticket.getStatus() != TicketStatus.AI_SERVING)
            .map(HandoffTicket::fromTicket);
    }

    /** 坐席接单：仅 PENDING 可推进，重复接单 fast-fail。 */
    public HandoffTicket claim(String id, String operator) {
        if (isLegacyMode()) {
            HandoffTicket ticket = requireLegacy(id);
            ticket.claim(operator, System.currentTimeMillis());
            legacyStore.update(ticket);
            return ticket;
        }
        HandoffTicket ticket = HandoffTicket.fromTicket(ticketService.claim(id, operator));
        log.info("handoff claimed on canonical ticket: id={}, operator={}", id, operator);
        return ticket;
    }

    /** 坐席处理完毕、回收给 AI：仅 CLAIMED 可推进，未接单先结案 fast-fail。 */
    public HandoffTicket resolve(String id, String note) {
        String operator = find(id).map(HandoffTicket::getClaimedBy).orElse(SYSTEM_OPERATOR);
        return resolve(id, note, operator == null ? SYSTEM_OPERATOR : operator);
    }

    /** 坐席结案：权威状态机 PROCESSING|ON_HOLD → RESOLVED。 */
    public HandoffTicket resolve(String id, String note, String operator) {
        if (isLegacyMode()) {
            HandoffTicket ticket = requireLegacy(id);
            ticket.resolve(note, System.currentTimeMillis());
            legacyStore.update(ticket);
            return ticket;
        }
        HandoffTicket ticket = HandoffTicket.fromTicket(ticketService.resolveHandoff(id, note, operator));
        log.info("handoff resolved on canonical ticket: id={}, operator={}", id, operator);
        return ticket;
    }

    /** 单一防御点：工单必须存在，否则 fast-fail。 */
    private HandoffTicket requireLegacy(String id) {
        return legacyStore.find(id).orElseThrow(() ->
            new NoSuchElementException("handoff not found: " + id));
    }

    private boolean isLegacyMode() {
        return legacyStore != null;
    }

    private static boolean wasHandedOff(Ticket ticket) {
        return ticket.getHandoffAtMs() > 0 || (ticket.getHandoffReason() != null
            && !ticket.getHandoffReason().isBlank());
    }
}
