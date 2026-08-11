package com.richard.fyoung.customerwork.capability.slotfilling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlotFillingStore SPI 单测（内存实现 + 进度持久化 / 恢复验证）。
 * @author owlzhangfq@gmail.com
 */
class SlotFillingStoreTest {

    private InMemorySlotFillingStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySlotFillingStore();
    }

    @Test
    void findOrCreate_shouldCreateNewProgress_whenAbsent() {
        SlotFillingProgress p = store.findOrCreate("s1:refund");
        assertNotNull(p);
        assertTrue(p.getCollected().isEmpty());
        assertNull(p.getAsking());
    }

    @Test
    void findOrCreate_shouldReturnSameProgress_whenExists() {
        SlotFillingProgress p1 = store.findOrCreate("s1:refund");
        p1.getCollected().put("orderId", "O123");
        p1.setAsking("reason");

        SlotFillingProgress p2 = store.findOrCreate("s1:refund");
        assertSame(p1, p2);
        assertEquals("O123", p2.getCollected().get("orderId"));
        assertEquals("reason", p2.getAsking());
    }

    @Test
    void save_shouldPersistProgress() {
        SlotFillingProgress p = new SlotFillingProgress();
        p.getCollected().put("orderId", "O456");
        p.setAsking("reason");

        store.save("s2:refund", p);

        SlotFillingProgress found = store.find("s2:refund").orElseThrow();
        assertEquals("O456", found.getCollected().get("orderId"));
        assertEquals("reason", found.getAsking());
    }

    @Test
    void find_nonExistent_shouldReturnEmpty() {
        assertTrue(store.find("non-existent").isEmpty());
    }

    @Test
    void delete_shouldRemoveProgress() {
        store.findOrCreate("s3:refund");
        store.delete("s3:refund");
        assertTrue(store.find("s3:refund").isEmpty());
    }

    @Test
    void save_null_shouldNoOp() {
        store.save("key", null);
        assertTrue(store.find("key").isEmpty());
    }

    @Test
    void slotFillingService_withStore_shouldSurviveStoreReplacement() {
        SlotFillingService svc = new SlotFillingService(store);
        SlotFillingForm form = SlotFillingForm.refundForm();

        // 轮1：开始收集
        SlotFillingResult r1 = svc.submit("s1", form, "我要退款");
        assertFalse(r1.isComplete());

        // 验证进度已持久化到 store
        SlotFillingProgress stored = store.find("s1:refund").orElseThrow();
        assertEquals("orderId", stored.getAsking());

        // 轮2：继续收集
        SlotFillingResult r2 = svc.submit("s1", form, "20260613001");
        assertFalse(r2.isComplete());
        assertEquals("20260613001", r2.getValues().get("orderId"));
    }
}
