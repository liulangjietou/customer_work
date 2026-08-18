package com.richard.fyoung.customerworkapp.service;

import com.richard.fyoung.customerwork.data.ticket.Ticket;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 用户会话根资源归属守卫测试。 */
class UserSessionGuardTest {

    private final TicketService ticketService = mock(TicketService.class);
    private final UserSessionGuard guard = new UserSessionGuard(ticketService);

    @Test
    void ownerShouldReceiveTicket() {
        Ticket ticket = ticket("user-1");
        when(ticketService.findBySession("session-1")).thenReturn(Optional.of(ticket));

        assertEquals(ticket, guard.requireOwned("session-1", "user-1"));
    }

    @Test
    void unknownAndForeignSessionShouldBothReturn404() {
        when(ticketService.findBySession("unknown")).thenReturn(Optional.empty());
        when(ticketService.findBySession("session-1")).thenReturn(Optional.of(ticket("user-1")));

        assertNotFound(() -> guard.requireOwned("unknown", "user-2"));
        assertNotFound(() -> guard.requireOwned("session-1", "user-2"));
    }

    private void assertNotFound(Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private Ticket ticket(String userId) {
        return Ticket.create("ticket-1", "session-1", userId, "title", TicketCategory.CONSULT);
    }
}
