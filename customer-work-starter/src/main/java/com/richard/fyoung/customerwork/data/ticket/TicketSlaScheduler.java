package com.richard.fyoung.customerwork.data.ticket;

import org.springframework.beans.factory.annotation.Autowired;
import com.richard.fyoung.customerwork.core.runtime.SchedulerLease;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Set;

/**
 * 工单 SLA 巡检器：周期扫描超期工单，分阶段处置。与 {@code HandoffSlaScheduler} 的"只告警不流转"不同，
 * 本调度器执行两个维度、彼此独立的检查——按状态定制的 SLA/自动流转，以及跨状态统一的用户空闲强关：
 *
 * <p><b>维度一：按状态定制的 SLA 检查</b>（各状态对应的兜底动作不同，非"是否自动关闭"这一件事）——</p>
 * <ul>
 *   <li>{@code WAITING_AGENT} 超 {@code sla-waiting-seconds} 未接单 / {@code PROCESSING} 超
 *       {@code sla-processing-seconds} 未处理完：无法自动替坐席接单/处理，仅 error 告警
 *       （错误码 {@code TICKET-SLA-BREACH}），交人工介入——这两条本身不会流转或关闭工单；</li>
 *   <li>{@code WAITING_CONFIRM} 超 {@code auto-confirm-seconds} 用户未确认：视为默认满意，
 *       自动确认（SYSTEM 发起）——这是合理兜底（长期无异议即认可）；</li>
 *   <li>{@code RESOLVED} 超 {@code auto-close-seconds}：自动关闭归档（SYSTEM 发起）。</li>
 * </ul>
 *
 * <p><b>维度二：{@link #idleCloseStale()} 用户空闲强关</b>——产品已确认全部进行中状态（AI_SERVING/
 * WAITING_AGENT/PROCESSING/ON_HOLD/WAITING_CONFIRM）统一按 {@code idle-close-seconds} 兜底：
 * 用户静默超阈值即 {@code forceClose} 直达 CLOSED（SYSTEM 发起），与该状态在维度一里"有没有定制兜底动作"
 * 无关。因此 {@code WAITING_AGENT}/{@code PROCESSING} 虽在维度一"无自动兜底"，仍会被维度二强关——
 * 二者不矛盾，是两条独立巡检线：维度一回答"这个状态该怎么流转"，维度二回答"用户是否已经不在了"。</p>
 *
 * <p><b>触发优先级</b>：同一张工单可能同时满足两个维度的条件，实际以先到达阈值的为准——默认配置下
 * {@code idle-close-seconds}（300s）远小于 {@code auto-confirm-seconds}（86400s）/
 * {@code auto-close-seconds}（259200s）/{@code sla-processing-seconds}（1800s），生产场景下通常是
 * idle-close 先触发；与 {@code sla-waiting-seconds} 默认值相同（均 300s），但两者计时锚点不同
 * （idle-close 按用户最后活跃时间，SLA 告警按进入 {@code WAITING_AGENT} 的 handoff 时间），谁先触发取决于
 * 用户静默是否早于进入该状态的时刻。两个维度由各自独立的 {@code autoFlow} 调用触发，互不感知、互不阻塞。</p>
 *
 * <p>整体受 {@code customer-work.ticket.sla-enabled} 开关控制，各阈值 &lt;=0 时对应检查单独禁用——
 * 生产如需关闭 idle-close 兜底（例如需要人工判断静默工单是否真正结束），将
 * {@code customer-work.ticket.idle-close-seconds} 置 0 即可，不影响维度一的 SLA 告警/自动确认/自动关闭。
 * 自动流转按单张容错：单张流转异常只 error 日志，不影响其余工单。</p>
 * @author owlzhangfq@gmail.com
 */
public class TicketSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(TicketSlaScheduler.class);

    /** 多副本互斥；单副本部署下等价于直接执行。 */
    private final SchedulerLease schedulerLease;

    private static final String SLA_BREACH_CODE = "TICKET-SLA-BREACH";
    private static final String SLA_AUTO_FLOW_FAIL = "TICKET-SLA-AUTOFLOW-FAIL";
    private static final String SYSTEM_ACTOR = "sla-scheduler";
    private static final String AUTO_CONFIRM_NOTE = "SLA auto-confirm: no user objection in time window";
    private static final String AUTO_CLOSE_NOTE = "SLA auto-close: resolved and aged out";
    private static final String IDLE_CLOSE_NOTE = "idle timeout auto close: no user activity in time window";
    private static final int MILLIS_PER_SECOND = 1000;

    /** 空闲超时巡检覆盖的进行中状态（RESOLVED/CLOSED 已终态，不在此列）。 */
    private static final Set<TicketStatus> IDLE_SCAN_STATUSES = Set.of(
        TicketStatus.AI_SERVING, TicketStatus.WAITING_AGENT, TicketStatus.PROCESSING,
        TicketStatus.ON_HOLD, TicketStatus.WAITING_CONFIRM);

    private final CustomerWorkProperties properties;
    private final TicketService ticketService;

    /** 兼容既有显式构造（单副本 / 离线单测）：不加多副本互斥。 */
    public TicketSlaScheduler(CustomerWorkProperties properties, TicketService ticketService) {
        this(properties, ticketService, null);
    }

    @Autowired
    public TicketSlaScheduler(CustomerWorkProperties properties, TicketService ticketService, SchedulerLease schedulerLease) {
        this.properties = properties;
        this.ticketService = ticketService;
        this.schedulerLease = schedulerLease == null ? SchedulerLease.noLease() : schedulerLease;
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void checkSla() {
        // 工单 SLA：多副本会把同一条超时告警重复发出去，淹没真正需要处理的那条
        schedulerLease.runExclusively("ticket-sla", this::doCheckSla);
    }

    /** 一轮实际逻辑；单测入口直接调它，不经过多副本互斥。 */
    private void doCheckSla() {
        if (!properties.getTicket().isSlaEnabled()) {
            return;
        }
        checkWaitingAgentSla();
        checkProcessingSla();
        autoConfirmStale();
        autoCloseStale();
        idleCloseStale();
    }

    /** WAITING_AGENT 超阈值未接单：仅告警（无自动兜底）。 */
    void checkWaitingAgentSla() {
        long slaSeconds = properties.getTicket().getSlaWaitingSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * MILLIS_PER_SECOND;
        for (Ticket ticket : store(TicketStatus.WAITING_AGENT)) {
            if (ticket.getHandoffAtMs() > 0 && ticket.getHandoffAtMs() < threshold) {
                breach(ticket, TicketStatus.WAITING_AGENT, ticket.getHandoffAtMs(), slaSeconds);
            }
        }
    }

    /** PROCESSING 超阈值未处理完：仅告警（无自动兜底）。 */
    void checkProcessingSla() {
        long slaSeconds = properties.getTicket().getSlaProcessingSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * MILLIS_PER_SECOND;
        for (Ticket ticket : store(TicketStatus.PROCESSING)) {
            if (ticket.getClaimedAtMs() > 0 && ticket.getClaimedAtMs() < threshold) {
                breach(ticket, TicketStatus.PROCESSING, ticket.getClaimedAtMs(), slaSeconds);
            }
        }
    }

    /** WAITING_CONFIRM 超阈值用户未确认：自动确认（合理兜底）。 */
    void autoConfirmStale() {
        long slaSeconds = properties.getTicket().getAutoConfirmSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * MILLIS_PER_SECOND;
        for (Ticket ticket : store(TicketStatus.WAITING_CONFIRM)) {
            if (ticket.getUpdatedAtMs() < threshold) {
                autoFlow(() -> ticketService.confirm(ticket.getId(), TicketActorType.SYSTEM, SYSTEM_ACTOR),
                    ticket.getId(), "auto-confirm");
            }
        }
    }

    /** RESOLVED 超阈值：自动关闭归档。 */
    void autoCloseStale() {
        long slaSeconds = properties.getTicket().getAutoCloseSeconds();
        if (slaSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - slaSeconds * MILLIS_PER_SECOND;
        for (Ticket ticket : store(TicketStatus.RESOLVED)) {
            if (ticket.getResolvedAtMs() > 0 && ticket.getResolvedAtMs() < threshold) {
                autoFlow(() -> ticketService.close(ticket.getId(), AUTO_CLOSE_NOTE,
                    TicketActorType.SYSTEM, SYSTEM_ACTOR), ticket.getId(), "auto-close");
            }
        }
    }

    /**
     * 进行中工单用户空闲超时：{@code now - lastUserActiveAtMs > idle-close-seconds} 即强制关闭。
     *
     * <p>扫描所有进行中状态（{@link #IDLE_SCAN_STATUSES}），复用 {@code forceClose} 直达 CLOSED（不受
     * 普通 close 前置态限制），并广播关闭事件（下游 WS 监听器据此推给前端）。历史数据 lastUserActiveAtMs
     * 未刷新（&lt;=0）时用 updatedAtMs 兜底。{@code idle-close-seconds &lt;= 0} 时禁用本轮巡检。</p>
     */
    void idleCloseStale() {
        long idleSeconds = properties.getTicket().getIdleCloseSeconds();
        if (idleSeconds <= 0) {
            return;
        }
        long threshold = System.currentTimeMillis() - idleSeconds * MILLIS_PER_SECOND;
        for (TicketStatus status : IDLE_SCAN_STATUSES) {
            for (Ticket ticket : store(status)) {
                long lastActive = effectiveLastActive(ticket);
                if (lastActive < threshold) {
                    autoFlow(() -> ticketService.forceClose(ticket.getId(), IDLE_CLOSE_NOTE,
                        TicketActorType.SYSTEM, SYSTEM_ACTOR), ticket.getId(), "idle-close");
                }
            }
        }
    }

    /** 用户最后活跃时间：正常取 lastUserActiveAtMs，历史数据未刷新（&lt;=0）时用 updatedAtMs 兜底。 */
    private long effectiveLastActive(Ticket ticket) {
        return ticket.getLastUserActiveAtMs() > 0 ? ticket.getLastUserActiveAtMs() : ticket.getUpdatedAtMs();
    }

    private List<Ticket> store(TicketStatus status) {
        return ticketService.findByStatus(status);
    }

    private void breach(Ticket ticket, TicketStatus stage, long sinceMs, long slaSeconds) {
        long ageSeconds = (System.currentTimeMillis() - sinceMs) / MILLIS_PER_SECOND;
        log.error("ticket SLA breach, code={}, stage={}, id={}, ageSeconds={}, slaSeconds={}",
            SLA_BREACH_CODE, stage, ticket.getId(), ageSeconds, slaSeconds);
    }

    /** 单张自动流转容错：异常只 error 日志、不中断其余工单。 */
    private void autoFlow(Runnable action, String ticketId, String kind) {
        try {
            action.run();
            log.info("ticket SLA {} done: id={}", kind, ticketId);
        } catch (Exception e) {
            log.error("ticket SLA auto-flow failed, code={}, kind={}, id={}",
                SLA_AUTO_FLOW_FAIL, kind, ticketId, e);
        }
    }

    /** 可被单测调用的同步入口（跳过 @Scheduled 注解）。 */
    public void runSlaCheck() {
        doCheckSla();
    }
}
