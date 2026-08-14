package com.richard.fyoung.customerwork.data.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** 内存模式工单事件发布器：同步广播，保持离线模式原有行为。 */
public class ImmediateTicketEventPublisher implements TicketEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ImmediateTicketEventPublisher.class);

    private final ObjectProvider<TicketEventListener> listenerProvider;

    public ImmediateTicketEventPublisher(ObjectProvider<TicketEventListener> listenerProvider) {
        this.listenerProvider = listenerProvider;
    }

    @Override
    public void publish(Ticket ticket, TicketEvent event) {
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
