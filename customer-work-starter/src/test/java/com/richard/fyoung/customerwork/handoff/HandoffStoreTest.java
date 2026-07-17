package com.richard.fyoung.customerwork.handoff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HandoffStore SPI 单测（内存实现 + HandoffService 委托存储逻辑与状态机）。
 * @author owlzhangfq@gmail.com
 */
class HandoffStoreTest {

    private InMemoryHandoffStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryHandoffStore();
    }

    @Test
    void saveAndFind_shouldStoreAndRetrieveTicket() {
        HandoffTicket ticket = new HandoffTicket("HO-1", "s1", "test", System.currentTimeMillis());
        store.save(ticket);

        Optional<HandoffTicket> found = store.find("HO-1");
        assertTrue(found.isPresent());
        assertEquals("HO-1", found.get().getId());
        assertEquals(HandoffStatus.PENDING, found.get().getStatus());
    }

    @Test
    void find_nonExistent_shouldReturnEmpty() {
        assertTrue(store.find("HO-missing").isEmpty());
    }

    @Test
    void findAll_shouldReturnAllTickets() {
        store.save(new HandoffTicket("HO-1", "s1", "r1", 1L));
        store.save(new HandoffTicket("HO-2", "s2", "r2", 2L));

        assertEquals(2, store.findAll().size());
    }

    @Test
    void findByStatus_shouldFilterCorrectly() {
        HandoffTicket pending = new HandoffTicket("HO-1", "s1", "r", 1L);
        HandoffTicket claimed = new HandoffTicket("HO-2", "s2", "r", 2L);
        claimed.claim("alice", System.currentTimeMillis());

        store.save(pending);
        store.save(claimed);

        assertEquals(1, store.findByStatus(HandoffStatus.PENDING).size());
        assertEquals(1, store.findByStatus(HandoffStatus.CLAIMED).size());
    }

    @Test
    void update_shouldPersistStateChange() {
        HandoffTicket ticket = new HandoffTicket("HO-1", "s1", "r", 1L);
        store.save(ticket);

        ticket.claim("bob", System.currentTimeMillis());
        store.update(ticket);

        HandoffTicket stored = store.find("HO-1").orElseThrow();
        assertEquals(HandoffStatus.CLAIMED, stored.getStatus());
        assertEquals("bob", stored.getClaimedBy());
    }

    @Test
    void save_null_shouldNoOp() {
        store.save(null);
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    void handoffService_withStore_shouldDelegateAndAdvanceStateMachine() {
        HandoffService svc = new HandoffService(store);

        HandoffTicket created = svc.create("s1", "用户投诉升级");
        assertEquals(HandoffStatus.PENDING, created.getStatus());

        HandoffTicket claimed = svc.claim(created.getId(), "alice");
        assertEquals(HandoffStatus.CLAIMED, claimed.getStatus());
        assertEquals("alice", claimed.getClaimedBy());
        assertSame(created, claimed);

        HandoffTicket resolved = svc.resolve(created.getId(), "已安抚，问题解决");
        assertEquals(HandoffStatus.RESOLVED, resolved.getStatus());
        assertEquals("已安抚，问题解决", resolved.getResolutionNote());

        // 持久化层应反映最终状态
        HandoffTicket stored = store.find(created.getId()).orElseThrow();
        assertEquals(HandoffStatus.RESOLVED, stored.getStatus());
    }

    @Test
    void handoffService_defaultConstructor_shouldWork() {
        HandoffService svc = new HandoffService();
        HandoffTicket ticket = svc.create("s1", "test");
        assertEquals(HandoffStatus.PENDING, ticket.getStatus());
        assertEquals(1, svc.list().size());
    }

    @Test
    void claim_alreadyClaimed_shouldFastFail() {
        HandoffService svc = new HandoffService(store);
        HandoffTicket ticket = svc.create("s1", "test");
        svc.claim(ticket.getId(), "alice");

        assertThrows(IllegalStateException.class, () -> svc.claim(ticket.getId(), "bob"));
    }

    @Test
    void resolve_beforeClaim_shouldFastFail() {
        HandoffService svc = new HandoffService(store);
        HandoffTicket ticket = svc.create("s1", "test");

        assertThrows(IllegalStateException.class, () -> svc.resolve(ticket.getId(), "note"));
    }

    @Test
    void resolve_afterResolved_shouldFastFail() {
        HandoffService svc = new HandoffService(store);
        HandoffTicket ticket = svc.create("s1", "test");
        svc.claim(ticket.getId(), "alice");
        svc.resolve(ticket.getId(), "done");

        assertThrows(IllegalStateException.class, () -> svc.resolve(ticket.getId(), "again"));
    }

    @Test
    void claimOrResolve_notFound_shouldThrowNoSuchElement() {
        HandoffService svc = new HandoffService(store);
        assertThrows(NoSuchElementException.class, () -> svc.claim("HO-missing", "alice"));
        assertThrows(NoSuchElementException.class, () -> svc.resolve("HO-missing", "note"));
    }

    @Test
    void listByStatus_shouldDelegateToStore() {
        HandoffService svc = new HandoffService(store);
        svc.create("s1", "r1");
        HandoffTicket t2 = svc.create("s2", "r2");
        svc.claim(t2.getId(), "alice");

        List<HandoffTicket> pending = svc.listByStatus(HandoffStatus.PENDING);
        assertEquals(1, pending.size());
        assertEquals("s1", pending.get(0).getSessionId());
    }
}
