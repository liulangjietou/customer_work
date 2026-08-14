package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.safety.security.AgentAuthWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HandoffControllerTest {

    @Test
    void shouldUseVerifiedAgentIdentityWhenClaiming() {
        HandoffService service = mock(HandoffService.class);
        AuditSink auditSink = mock(AuditSink.class);
        HandoffTicket ticket = new HandoffTicket("HO-1", "session-1", "need help", 1L);
        when(service.claim("HO-1", "agent-7")).thenReturn(ticket);
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/handoffs/HO-1/claim"));
        exchange.getAttributes().put(AgentAuthWebFilter.AGENT_ID_ATTR, "agent-7");

        new HandoffController(service, auditSink).claim("HO-1", exchange).block();

        verify(service).claim("HO-1", "agent-7");
        verify(auditSink).record(eq("handoff-decision"),
            argThat(fields -> "agent-7".equals(fields.get("operator"))));
    }

    @Test
    void shouldRejectMissingVerifiedAgentIdentity() {
        HandoffController controller = new HandoffController(
            mock(HandoffService.class), mock(AuditSink.class));
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/customer/handoffs/HO-1/claim"));

        assertThrows(ResponseStatusException.class, () -> controller.claim("HO-1", exchange));
    }
}
