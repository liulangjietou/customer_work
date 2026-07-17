package com.richard.fyoung.customerwork.routing;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内坐席库单测：演示种子装载 + save round-trip。
 * @author owlzhangfq@gmail.com
 */
class InMemorySeatAgentStoreTest {

    @Test
    void constructor_shouldLoadDemoSeeds() {
        InMemorySeatAgentStore store = new InMemorySeatAgentStore();
        assertTrue(store.findAll().stream().anyMatch(s -> "SEAT-1001".equals(s.getId())));
        // 含一个离线坐席用于打分排除演示
        assertTrue(store.findAll().stream().anyMatch(s -> !s.isOnline()));
    }

    @Test
    void save_shouldUpsertById() {
        InMemorySeatAgentStore store = new InMemorySeatAgentStore();
        int before = store.findAll().size();
        store.save(new SeatAgent("SEAT-NEW", "新坐席", Set.of("refund"), 5, 0, true, "g"));
        assertEquals(before + 1, store.findAll().size());
        store.save(new SeatAgent("SEAT-NEW", "新坐席改名", Set.of("refund"), 5, 1, true, "g"));
        assertEquals(before + 1, store.findAll().size(), "同 ID 应更新而非新增");
    }

    @Test
    void seat_shouldNormalizeSkillsToLowerCase() {
        SeatAgent seat = new SeatAgent("S", "n", Set.of("Refund", "LOGISTICS"), 5, 0, true, "g");
        assertTrue(seat.hasSkill("refund"));
        assertTrue(seat.hasSkill("Logistics"));
    }
}
