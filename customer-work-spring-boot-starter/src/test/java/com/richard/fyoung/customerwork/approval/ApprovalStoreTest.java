package com.richard.fyoung.customerwork.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApprovalStore SPI 单测（内存实现 + PendingApprovalService 委托存储逻辑）。
 * @author owlzhangfq@gmail.com
 */
class ApprovalStoreTest {

    private InMemoryApprovalStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryApprovalStore();
    }

    @Test
    void saveAndFind_shouldStoreAndRetrieveApproval() {
        ApprovalRequest req = new ApprovalRequest("AP-1", ApprovalType.REFUND,
            "s1", "O1", "299.00", "test", System.currentTimeMillis());
        store.save(req);

        Optional<ApprovalRequest> found = store.find("AP-1");
        assertTrue(found.isPresent());
        assertEquals("AP-1", found.get().getId());
        assertEquals(ApprovalStatus.PENDING, found.get().getStatus());
    }

    @Test
    void find_nonExistent_shouldReturnEmpty() {
        assertTrue(store.find("AP-missing").isEmpty());
    }

    @Test
    void findAll_shouldReturnAllApprovals() {
        store.save(new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1L));
        store.save(new ApprovalRequest("AP-2", ApprovalType.TRANSFER_HUMAN, "s2", "O2", "0", "r", 2L));

        List<ApprovalRequest> all = store.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void findByStatus_shouldFilterCorrectly() {
        ApprovalRequest pending = new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1L);
        ApprovalRequest decided = new ApprovalRequest("AP-2", ApprovalType.REFUND, "s2", "O2", "200", "r", 2L);
        decided.approve("alice", null, System.currentTimeMillis());

        store.save(pending);
        store.save(decided);

        assertEquals(1, store.findByStatus(ApprovalStatus.PENDING).size());
        assertEquals(1, store.findByStatus(ApprovalStatus.APPROVED).size());
    }

    @Test
    void update_shouldPersistStateChange() {
        ApprovalRequest req = new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1L);
        store.save(req);

        req.approve("bob", "ok", System.currentTimeMillis());
        store.update(req);

        ApprovalRequest stored = store.find("AP-1").orElseThrow();
        assertEquals(ApprovalStatus.APPROVED, stored.getStatus());
        assertEquals("bob", stored.getOperator());
    }

    @Test
    void delete_shouldRemoveApproval() {
        store.save(new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1", "100", "r", 1L));
        store.delete("AP-1");
        assertTrue(store.find("AP-1").isEmpty());
    }

    @Test
    void save_null_shouldNoOp() {
        store.save(null);
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    void pendingApprovalService_withStore_shouldDelegateCorrectly() {
        PendingApprovalService svc = new PendingApprovalService(store);
        AtomicReference<ApprovalRequest> approved = new AtomicReference<>();
        svc.onApprove(approved::set);

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "test");
        ApprovalRequest result = svc.approve(req.getId(), "alice");

        assertEquals(ApprovalStatus.APPROVED, result.getStatus());
        assertSame(req, approved.get());
        // 持久化层应反映状态变更
        ApprovalRequest stored = store.find(req.getId()).orElseThrow();
        assertEquals(ApprovalStatus.APPROVED, stored.getStatus());
    }

    @Test
    void pendingApprovalService_defaultConstructor_shouldWork() {
        PendingApprovalService svc = new PendingApprovalService();
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "50", "test");
        assertEquals(ApprovalStatus.PENDING, req.getStatus());
        assertEquals(1, svc.list().size());
    }

    @Test
    void pendingApprovalService_deny_shouldUpdateStore() {
        PendingApprovalService svc = new PendingApprovalService(store);
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "50", "test");
        svc.deny(req.getId(), "bob", "rejected");

        ApprovalRequest stored = store.find(req.getId()).orElseThrow();
        assertEquals(ApprovalStatus.DENIED, stored.getStatus());
        assertEquals("rejected", stored.getDecisionNote());
    }
}
