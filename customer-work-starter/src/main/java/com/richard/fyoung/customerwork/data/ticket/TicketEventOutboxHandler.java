package com.richard.fyoung.customerwork.data.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richard.fyoung.customerwork.data.outbox.OutboxHandler;
import com.richard.fyoung.customerwork.data.outbox.OutboxMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** ticket-event Outbox Handler：还原事件发生当刻快照并调用原有监听器扩展点。 */
public class TicketEventOutboxHandler implements OutboxHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketEventOutboxHandler.class);

    private final ObjectMapper objectMapper;
    private final ObjectProvider<TicketEventListener> listenerProvider;

    public TicketEventOutboxHandler(ObjectMapper objectMapper,
                                    ObjectProvider<TicketEventListener> listenerProvider) {
        this.objectMapper = objectMapper;
        this.listenerProvider = listenerProvider;
    }

    @Override
    public String type() {
        return OutboxTicketEventPublisher.TYPE;
    }

    @Override
    public void handle(OutboxMessage message) throws Exception {
        TicketEventPayload payload = objectMapper.readValue(message.getPayload(), TicketEventPayload.class);
        Exception firstFailure = null;
        for (TicketEventListener listener : listenerProvider) {
            try {
                listener.onTicketEvent(payload.toTicket(), payload.event());
            } catch (Exception e) {
                log.error("ticket event listener failed, code={}, eventId={}, listener={}",
                    "TICKET-EVENT-LISTENER-FAIL", payload.event().id(),
                    listener.getClass().getName(), e);
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
