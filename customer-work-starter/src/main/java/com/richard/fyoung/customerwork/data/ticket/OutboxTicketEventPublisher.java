package com.richard.fyoung.customerwork.data.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.outbox.OutboxService;

/** JDBC 工单事件发布器：把快照写入同库 Outbox，由事务保证与工单、审计事件原子提交。 */
public class OutboxTicketEventPublisher implements TicketEventPublisher {

    public static final String TYPE = "ticket-event";

    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public OutboxTicketEventPublisher(OutboxService outboxService, ObjectMapper objectMapper) {
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Ticket ticket, TicketEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(TicketEventPayload.from(ticket, event));
            outboxService.publish(TYPE, ticket.getId(), payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize ticket event: " + ticket.getId(), e);
        }
    }
}
