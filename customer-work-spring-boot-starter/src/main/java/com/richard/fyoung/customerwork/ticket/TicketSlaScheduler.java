package com.richard.fyoung.customerwork.ticket;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 工单 SLA 巡检器：周期扫描超期工单，分阶段处置。
 *
 * <p><b>与 {@code HandoffSlaScheduler} 的"只告警不流转"不同</b>：本调度器对存在合理兜底动作的两个状态
 * 执行自动流转，属有意设计——</p>
 * <ul>
 *   <li>{@code WAITING_AGENT} 超 {@code sla-waiting-seconds} 未接单 / {@code PROCESSING} 超
 *       {@code sla-processing-seconds} 未处理完：无自动兜底动作（不能替坐席接单/处理），仅 error 告警
 *       （错误码 {@code TICKET-SLA-BREACH}），交人工介入；</li>
 *   <li>{@code WAITING_CONFIRM} 超 {@code auto-confirm-seconds} 用户未确认：视为默认满意，
 *       自动确认（SYSTEM 发起）——这是合理兜底（长期无异议即认可）；</li>
 *   <li>{@code RESOLVED} 超 {@code auto-close-seconds}：自动关闭归档（SYSTEM 发起）。</li>
 * </ul>
 *
 * <p>整体受 {@code customer-work.ticket.sla-enabled} 开关控制，各阈值 &lt;=0 时对应检查单独禁用。
 * 自动流转按单张容错：单张流转异常只 error 日志，不影响其余工单。</p>
 * @author owlzhangfq@gmail.com
 */
public class TicketSlaScheduler {

    private static final Logger log = LoggerFactory.getLogger(TicketSlaScheduler.class);

    private static final String SLA_BREACH_CODE = "TICKET-SLA-BREACH";
    private static final String SLA_AUTO_FLOW_FAIL = "TICKET-SLA-AUTOFLOW-FAIL";
    private static final String SYSTEM_ACTOR = "sla-scheduler";
    private static final String AUTO_CONFIRM_NOTE = "SLA auto-confirm: no user objection in time window";
    private static final String AUTO_CLOSE_NOTE = "SLA auto-close: resolved and aged out";
    private static final int MILLIS_PER_SECOND = 1000;

    private final CustomerWorkProperties properties;
    private final TicketService ticketService;

    public TicketSlaScheduler(CustomerWorkProperties properties, TicketService ticketService) {
        this.properties = properties;
        this.ticketService = ticketService;
    }

    @Scheduled(fixedDelayString = "${customer-work.runtime.scheduler-fixed-delay-ms:60000}")
    public void checkSla() {
        if (!properties.getTicket().isSlaEnabled()) {
            return;
        }
        checkWaitingAgentSla();
        checkProcessingSla();
        autoConfirmStale();
        autoCloseStale();
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
        checkSla();
    }
}
