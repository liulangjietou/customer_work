package com.richard.fyoung.customerwork.slotfilling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮槽位收集状态机单测（离线确定性）：逐轮收集 / 首句抽取 / 完成清理。
 * @author owlzhangfq@gmail.com
 */
class SlotFillingServiceTest {

    private final SlotFillingService svc = new SlotFillingService();

    @Test
    void shouldCollectAcrossMultipleTurns() {
        String s = "sess-1";
        SlotFillingForm form = SlotFillingForm.refundForm();

        // 轮1：只说"我要退款" → 缺订单号，追问
        SlotFillingResult r1 = svc.submit(s, form, "我要退款");
        assertFalse(r1.isComplete());
        assertTrue(r1.getNextPrompt().contains("订单号"));

        // 轮2：给订单号 → 正则抽取 → 缺原因，追问
        SlotFillingResult r2 = svc.submit(s, form, "20260613001");
        assertFalse(r2.isComplete());
        assertEquals("20260613001", r2.getValues().get("orderId"));
        assertTrue(r2.getNextPrompt().contains("原因"));

        // 轮3：给原因（自由文本）→ 收齐
        SlotFillingResult r3 = svc.submit(s, form, "质量问题");
        assertTrue(r3.isComplete());
        assertEquals("20260613001", r3.getValues().get("orderId"));
        assertEquals("质量问题", r3.getValues().get("reason"));
    }

    @Test
    void shouldExtractOrderIdFromFirstUtterance() {
        String s = "sess-2";
        SlotFillingForm form = SlotFillingForm.refundForm();

        // 首句即含订单号 → 直接抽取，只追问原因
        SlotFillingResult r1 = svc.submit(s, form, "订单 20260613001 我要退款");
        assertFalse(r1.isComplete());
        assertEquals("20260613001", r1.getValues().get("orderId"));
        assertTrue(r1.getNextPrompt().contains("原因"));

        SlotFillingResult r2 = svc.submit(s, form, "七天无理由");
        assertTrue(r2.isComplete());
        assertEquals("七天无理由", r2.getValues().get("reason"));
    }

    @Test
    void shouldResetProgressAfterCompletion() {
        String s = "sess-3";
        SlotFillingForm form = SlotFillingForm.refundForm();
        svc.submit(s, form, "退款 20260613001");
        svc.submit(s, form, "拍错了");
        // 完成后状态已清理：再次提交从头开始（缺订单号）
        SlotFillingResult again = svc.submit(s, form, "我又要退款");
        assertFalse(again.isComplete());
        assertTrue(again.getNextPrompt().contains("订单号"));
    }
}
