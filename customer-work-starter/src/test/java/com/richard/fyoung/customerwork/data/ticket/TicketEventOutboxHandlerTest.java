package com.richard.fyoung.customerwork.data.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.outbox.OutboxMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 Outbox 使用事件发生当刻快照，而不是异步消费时再读已变化的工单。 */
class TicketEventOutboxHandlerTest {

    @Test
    void handle_shouldRestoreTicketSnapshotAndEvent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Ticket ticket = Ticket.create("TK-1", "S-1", "U-1", "title", TicketCategory.ORDER);
        ticket.requestHandoff("human");
        TicketEvent event = TicketEvent.of(ticket.getId(), TicketEventType.REQUEST_HANDOFF,
            TicketStatus.AI_SERVING, TicketStatus.WAITING_AGENT, TicketActorType.USER, "U-1", "human")
            .withId(12L);
        String json = mapper.writeValueAsString(TicketEventPayload.from(ticket, event));
        AtomicReference<Ticket> receivedTicket = new AtomicReference<>();
        AtomicReference<TicketEvent> receivedEvent = new AtomicReference<>();
        TicketEventListener listener = (snapshot, deliveredEvent) -> {
            receivedTicket.set(snapshot);
            receivedEvent.set(deliveredEvent);
        };
        TicketEventOutboxHandler handler = new TicketEventOutboxHandler(mapper,
            TicketTestSupport.providerOf(listener));

        handler.handle(new OutboxMessage("MSG-1", OutboxTicketEventPublisher.TYPE, "TK-1", json, 1L));

        assertEquals(TicketStatus.WAITING_AGENT, receivedTicket.get().getStatus());
        assertEquals(12L, receivedEvent.get().id());
    }
}
