package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.data.ticket.InMemoryTicketStore;
import com.richard.fyoung.customerwork.data.ticket.TicketCategory;
import com.richard.fyoung.customerwork.data.ticket.TicketService;
import com.richard.fyoung.customerwork.data.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HandoffService} Spring 装配回归：生产构造必须复用容器中的权威 {@link TicketService}，
 * 且历史 {@link HandoffStore} 不再注册为第二权威源。
 * @author owlzhangfq@gmail.com
 */
class HandoffServiceWiringTest {

    @Test
    void springServiceShouldUseInjectedCanonicalTicketService() {
        TicketService ticketService = new TicketService(new InMemoryTicketStore(), null);
        new ApplicationContextRunner()
            .withBean(TicketService.class, () -> ticketService)
            .withBean(HandoffService.class)
            .run(context -> {
                HandoffService service = context.getBean(HandoffService.class);
                ticketService.createForSession("s1", "u1", "test", TicketCategory.OTHER);
                HandoffTicket created = service.create("s1", "need human");

                assertTrue(created.getId().startsWith("TK-"));
                assertEquals(TicketStatus.WAITING_AGENT,
                    ticketService.find(created.getId()).orElseThrow().getStatus());
            });
    }

    @Test
    void legacyHandoffConfigShouldNotRegisterSecondAuthority() {
        new ApplicationContextRunner()
            .withUserConfiguration(HandoffConfig.class)
            .run(context -> assertFalse(context.containsBean("handoffStore")));
    }
}
