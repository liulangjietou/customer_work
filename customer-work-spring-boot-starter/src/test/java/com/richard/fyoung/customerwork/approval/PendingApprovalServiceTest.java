package com.richard.fyoung.customerwork.approval;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工审批服务单测（Human-in-the-Loop 闭环）：状态机 + 决策回调 + fast-fail 边界。
 * @author owlzhangfq@gmail.com
 */
class PendingApprovalServiceTest {

    @Test
    void submit_shouldCreatePendingAndBeListable() {
        PendingApprovalService svc = new PendingApprovalService();
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "七天无理由");

        assertEquals(ApprovalStatus.PENDING, req.getStatus());
        assertTrue(req.getId().startsWith("AP-"));
        assertEquals(1, svc.listByStatus(ApprovalStatus.PENDING).size());
        assertSame(req, svc.find(req.getId()).orElseThrow());
    }

    @Test
    void approve_shouldTransitionAndTriggerCallback() {
        PendingApprovalService svc = new PendingApprovalService();
        AtomicReference<ApprovalRequest> approved = new AtomicReference<>();
        svc.onApprove(approved::set);

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "七天无理由");
        ApprovalRequest out = svc.approve(req.getId(), "alice");

        assertEquals(ApprovalStatus.APPROVED, out.getStatus());
        assertEquals("alice", out.getOperator());
        assertSame(req, approved.get(), "onApprove 回调应被触发并携带该审批单");
        assertTrue(svc.listByStatus(ApprovalStatus.PENDING).isEmpty());
    }

    @Test
    void deny_shouldTransitionAndTriggerCallback() {
        PendingApprovalService svc = new PendingApprovalService();
        AtomicReference<ApprovalRequest> denied = new AtomicReference<>();
        svc.onDeny(denied::set);

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        ApprovalRequest out = svc.deny(req.getId(), "bob", "金额存疑");

        assertEquals(ApprovalStatus.DENIED, out.getStatus());
        assertEquals("金额存疑", out.getDecisionNote());
        assertSame(req, denied.get());
    }

    @Test
    void decide_shouldRejectDoubleDecision() {
        PendingApprovalService svc = new PendingApprovalService();
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        svc.approve(req.getId(), "alice");

        // 已决策终态不可再变（fast-fail）
        assertThrows(IllegalStateException.class, () -> svc.approve(req.getId(), "alice"));
        assertThrows(IllegalStateException.class, () -> svc.deny(req.getId(), "bob", "x"));
    }

    @Test
    void decide_shouldFailFastWhenNotFound() {
        PendingApprovalService svc = new PendingApprovalService();
        assertThrows(NoSuchElementException.class, () -> svc.approve("AP-missing", "alice"));
        assertThrows(NoSuchElementException.class, () -> svc.deny("AP-missing", "bob", "x"));
    }
}
