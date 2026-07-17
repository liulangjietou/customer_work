package com.richard.fyoung.customerwork.approval;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(ExecutionStatus.EXECUTED, out.getExecutionStatus(), "回调成功应推进为 EXECUTED");
    }

    @Test
    void approve_shouldMarkExecutionFailed_whenCallbackThrows_withoutPropagating() {
        PendingApprovalService svc = new PendingApprovalService();
        svc.onApprove(r -> {
            throw new RuntimeException("downstream payout failed");
        });

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        // 决策本身不应因下游回调异常而失败（approve() 不应抛出）
        ApprovalRequest out = svc.approve(req.getId(), "alice");

        assertEquals(ApprovalStatus.APPROVED, out.getStatus(), "人工决策本身应成功，不受下游执行失败影响");
        assertEquals(ExecutionStatus.EXECUTE_FAILED, out.getExecutionStatus());
        assertEquals("downstream payout failed", out.getExecutionFailureReason());
        assertEquals(1, out.getExecutionAttempts());
    }

    @Test
    void retryExecutionFailures_shouldRetryAndMarkExecuted_onSubsequentSuccess() {
        PendingApprovalService svc = new PendingApprovalService();
        AtomicInteger callCount = new AtomicInteger(0);
        svc.onApprove(r -> {
            if (callCount.incrementAndGet() == 1) {
                throw new RuntimeException("transient failure");
            }
            // 第二次（重试）成功
        });

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        svc.approve(req.getId(), "alice");
        assertEquals(ExecutionStatus.EXECUTE_FAILED, svc.find(req.getId()).orElseThrow().getExecutionStatus());

        int retried = svc.retryExecutionFailures(3);

        assertEquals(1, retried, "应重试 1 张执行失败的审批单");
        assertEquals(ExecutionStatus.EXECUTED, svc.find(req.getId()).orElseThrow().getExecutionStatus());
        assertEquals(2, callCount.get(), "回调应共被调用 2 次（首次失败 + 重试成功）");
    }

    @Test
    void retryExecutionFailures_shouldStopAfterMaxAttempts() {
        PendingApprovalService svc = new PendingApprovalService();
        AtomicInteger callCount = new AtomicInteger(0);
        svc.onApprove(r -> {
            callCount.incrementAndGet();
            throw new RuntimeException("permanently broken");
        });

        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        svc.approve(req.getId(), "alice");   // 第 1 次（失败）

        svc.retryExecutionFailures(3);        // 第 2 次（失败，attempts=2 < 3，仍在候选内）
        svc.retryExecutionFailures(3);        // 第 3 次（失败，attempts=3，达到上限）
        int retriedAfterLimit = svc.retryExecutionFailures(3);   // attempts(3) 不再 < maxAttempts(3)，不再重试

        assertEquals(0, retriedAfterLimit, "达到最大尝试次数后不应再重试");
        assertEquals(3, callCount.get(), "回调总调用次数应止于 maxAttempts");
        assertEquals(ExecutionStatus.EXECUTE_FAILED, svc.find(req.getId()).orElseThrow().getExecutionStatus());
    }

    @Test
    void retryExecutionFailures_shouldNoop_whenMaxAttemptsAtMostOne() {
        PendingApprovalService svc = new PendingApprovalService();
        svc.onApprove(r -> {
            throw new RuntimeException("fail");
        });
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        svc.approve(req.getId(), "alice");

        assertEquals(0, svc.retryExecutionFailures(1), "maxAttempts<=1 时应直接不重试");
        assertEquals(0, svc.retryExecutionFailures(0));
    }

    @Test
    void deny_shouldNotPropagate_whenCallbackThrows() {
        PendingApprovalService svc = new PendingApprovalService();
        svc.onDeny(r -> {
            throw new RuntimeException("notification failed");
        });
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");

        ApprovalRequest out = svc.deny(req.getId(), "bob", "金额存疑");

        assertEquals(ApprovalStatus.DENIED, out.getStatus(), "拒绝决策不应受回调异常影响");
        assertFalse(svc.listByStatus(ApprovalStatus.PENDING).contains(out));
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
