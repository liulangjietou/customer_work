package com.richard.fyoung.customerwork.ticket;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单 SLA 巡检器单测：WAITING_AGENT/PROCESSING 超阈值仅告警（日志留证 + 不流转），
 * WAITING_CONFIRM/RESOLVED 超阈值真实自动流转并落事件；总开关关闭时全部停用。
 * @author owlzhangfq@gmail.com
 */
class TicketSlaSchedulerTest {

    private static final long OLD_MS_AGO = 5000;
    private static final String BREACH_CODE = "TICKET-SLA-BREACH";

    private CustomerWorkProperties propsOnlyWaiting() {
        CustomerWorkProperties p = zeroed();
        p.getTicket().setSlaWaitingSeconds(1);
        return p;
    }

    private CustomerWorkProperties zeroed() {
        CustomerWorkProperties p = new CustomerWorkProperties();
        p.getTicket().setSlaWaitingSeconds(0);
        p.getTicket().setSlaProcessingSeconds(0);
        p.getTicket().setAutoConfirmSeconds(0);
        p.getTicket().setAutoCloseSeconds(0);
        return p;
    }

    private TicketService serviceWith(InMemoryTicketStore store) {
        return new TicketService(store, TicketTestSupport.providerOf());
    }

    private long past() {
        return System.currentTimeMillis() - OLD_MS_AGO;
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TicketSlaScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private long breachCount(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
            .filter(e -> e.getLevel() == Level.ERROR)
            .filter(e -> e.getFormattedMessage().contains(BREACH_CODE))
            .count();
    }

    @Test
    void waitingAgentOverThreshold_shouldAlertOnlyNoFlow() {
        InMemoryTicketStore store = new InMemoryTicketStore();
        store.save(Ticket.reconstruct("TK-1", "s", "u", "t", TicketCategory.OTHER, TicketPriority.NORMAL,
            TicketStatus.WAITING_AGENT, null, "reason", null, 0,
            past(), past(), past(), 0, 0, 0));

        ListAppender<ILoggingEvent> appender = attachAppender();
        new TicketSlaScheduler(propsOnlyWaiting(), serviceWith(store)).runSlaCheck();

        assertTrue(breachCount(appender) >= 1, "WAITING_AGENT 超阈值应告警");
        assertEquals(TicketStatus.WAITING_AGENT, store.find("TK-1").orElseThrow().getStatus(),
            "仅告警不流转");
    }

    @Test
    void processingOverThreshold_shouldAlertOnlyNoFlow() {
        CustomerWorkProperties props = zeroed();
        props.getTicket().setSlaProcessingSeconds(1);

        InMemoryTicketStore store = new InMemoryTicketStore();
        store.save(Ticket.reconstruct("TK-2", "s", "u", "t", TicketCategory.OTHER, TicketPriority.NORMAL,
            TicketStatus.PROCESSING, "agent-1", "reason", null, 0,
            past(), past(), past(), past(), 0, 0));

        ListAppender<ILoggingEvent> appender = attachAppender();
        new TicketSlaScheduler(props, serviceWith(store)).runSlaCheck();

        assertTrue(breachCount(appender) >= 1, "PROCESSING 超阈值应告警");
        assertEquals(TicketStatus.PROCESSING, store.find("TK-2").orElseThrow().getStatus(), "仅告警不流转");
    }

    @Test
    void waitingConfirmOverThreshold_shouldAutoConfirmWithEvent() {
        CustomerWorkProperties props = zeroed();
        props.getTicket().setAutoConfirmSeconds(1);

        InMemoryTicketStore store = new InMemoryTicketStore();
        store.save(Ticket.reconstruct("TK-3", "s", "u", "t", TicketCategory.OTHER, TicketPriority.NORMAL,
            TicketStatus.WAITING_CONFIRM, "agent-1", "reason", "done", 0,
            past(), past(), past(), past(), 0, 0));

        TicketService svc = serviceWith(store);
        new TicketSlaScheduler(props, svc).runSlaCheck();

        assertEquals(TicketStatus.RESOLVED, store.find("TK-3").orElseThrow().getStatus(), "应自动确认");
        assertTrue(svc.findEvents("TK-3").stream()
                .anyMatch(e -> e.eventType() == TicketEventType.CONFIRM
                    && e.actorType() == TicketActorType.SYSTEM),
            "自动确认应有 SYSTEM 发起的 CONFIRM 事件");
    }

    @Test
    void resolvedOverThreshold_shouldAutoCloseWithEvent() {
        CustomerWorkProperties props = zeroed();
        props.getTicket().setAutoCloseSeconds(1);

        InMemoryTicketStore store = new InMemoryTicketStore();
        store.save(Ticket.reconstruct("TK-4", "s", "u", "t", TicketCategory.OTHER, TicketPriority.NORMAL,
            TicketStatus.RESOLVED, "agent-1", "reason", "done", 0,
            past(), past(), 0, past(), past(), 0));

        TicketService svc = serviceWith(store);
        new TicketSlaScheduler(props, svc).runSlaCheck();

        assertEquals(TicketStatus.CLOSED, store.find("TK-4").orElseThrow().getStatus(), "应自动关闭");
        assertTrue(svc.findEvents("TK-4").stream()
                .anyMatch(e -> e.eventType() == TicketEventType.CLOSE
                    && e.actorType() == TicketActorType.SYSTEM),
            "自动关闭应有 SYSTEM 发起的 CLOSE 事件");
    }

    @Test
    void slaDisabled_shouldDoNothing() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getTicket().setSlaEnabled(false);
        props.getTicket().setAutoConfirmSeconds(1);

        InMemoryTicketStore store = new InMemoryTicketStore();
        store.save(Ticket.reconstruct("TK-5", "s", "u", "t", TicketCategory.OTHER, TicketPriority.NORMAL,
            TicketStatus.WAITING_CONFIRM, "agent-1", "reason", "done", 0,
            past(), past(), past(), past(), 0, 0));

        new TicketSlaScheduler(props, serviceWith(store)).runSlaCheck();

        assertEquals(TicketStatus.WAITING_CONFIRM, store.find("TK-5").orElseThrow().getStatus(),
            "总开关关闭时不应有任何流转");
    }
}
