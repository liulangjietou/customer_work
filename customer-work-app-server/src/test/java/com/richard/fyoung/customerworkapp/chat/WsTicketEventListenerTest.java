package com.richard.fyoung.customerworkapp.chat;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketActorType;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketEvent;
import com.richard.fyoung.customerwork.data.ticket.TicketEventType;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import com.richard.fyoung.customerwork.infra.ws.WsFrame;
import com.richard.fyoung.customerwork.infra.ws.WsSessionRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WsTicketEventListenerTest {

    @Test
    void shouldExposeStableEventIdForClientDeduplication() {
        WsSessionRegistry registry = mock(WsSessionRegistry.class);
        Ticket ticket = Ticket.create("TK-1", "S-1", "U-1", "help", TicketCategory.CONSULT);
        ticket.requestHandoff("human");
        TicketEvent event = new TicketEvent(17L, ticket.getId(), TicketEventType.REQUEST_HANDOFF,
            TicketStatus.AI_SERVING, TicketStatus.WAITING_AGENT, TicketActorType.USER, "U-1", null, 2L);

        new WsTicketEventListener(registry).onTicketEvent(ticket, event);

        ArgumentCaptor<WsFrame> eventFrame = ArgumentCaptor.forClass(WsFrame.class);
        verify(registry).pushToUser(eq("U-1"), eventFrame.capture());
        assertEquals(17L, data(eventFrame.getValue()).get("eventId"));

        ArgumentCaptor<WsFrame> newFrame = ArgumentCaptor.forClass(WsFrame.class);
        verify(registry).broadcastToAgents(newFrame.capture());
        assertEquals(17L, data(newFrame.getValue()).get("eventId"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(WsFrame frame) {
        return (Map<String, Object>) frame.data();
    }
}
