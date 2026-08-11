package com.richard.fyoung.customerwork.capability.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工单优先级枚举单测：安全解析与权重。
 * @author owlzhangfq@gmail.com
 */
class TicketPriorityTest {

    @Test
    void fromName_shouldParseKnownValuesCaseInsensitively() {
        assertEquals(TicketPriority.URGENT, TicketPriority.fromName("urgent"));
        assertEquals(TicketPriority.HIGH, TicketPriority.fromName(" HIGH "));
    }

    @Test
    void fromName_shouldFallbackToMedium_forUnknownOrBlank() {
        assertEquals(TicketPriority.MEDIUM, TicketPriority.fromName(null));
        assertEquals(TicketPriority.MEDIUM, TicketPriority.fromName(""));
        assertEquals(TicketPriority.MEDIUM, TicketPriority.fromName("P0"));
    }

    @Test
    void weight_shouldBeMonotonic() {
        assertEquals(TicketPriority.MAX_WEIGHT, TicketPriority.URGENT.weight());
        org.junit.jupiter.api.Assertions.assertTrue(
            TicketPriority.LOW.weight() < TicketPriority.MEDIUM.weight());
    }
}
