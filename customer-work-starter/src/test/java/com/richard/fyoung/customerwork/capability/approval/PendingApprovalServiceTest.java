package com.richard.fyoung.customerwork.capability.approval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void approve_shouldNeverReportExecuted_whenProductionHandlerIsMissing() {
        PendingApprovalService svc = new PendingApprovalService(new InMemoryApprovalStore());
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");

        ApprovalRequest out = svc.approve(req.getId(), "alice");

        assertEquals(ApprovalStatus.APPROVED, out.getStatus());
        assertEquals(ExecutionStatus.EXECUTE_FAILED, out.getExecutionStatus());
        assertEquals("approval execution handler not configured", out.getExecutionFailureReason());
    }

    @Test
    void approve_shouldExecuteExactlyOnce_whenTwoOperatorsDecideConcurrently() throws Exception {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        AtomicInteger executions = new AtomicInteger();
        @SuppressWarnings("unchecked")
        ObjectProvider<ApprovalExecutionHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(context -> executions.incrementAndGet());
        PendingApprovalService svc = new PendingApprovalService(store, provider);
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger decisions = new AtomicInteger();

        List<CompletableFuture<Void>> futures = List.of("alice", "bob").stream()
            .map(operator -> CompletableFuture.runAsync(() -> {
                try {
                    start.await(3, TimeUnit.SECONDS);
                    svc.approve(req.getId(), operator);
                    decisions.incrementAndGet();
                } catch (IllegalStateException ignored) {
                    // 另一位操作者已完成 PENDING -> APPROVED CAS。
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }))
            .toList();
        start.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);

        assertEquals(1, decisions.get());
        assertEquals(1, executions.get());
        assertEquals(ExecutionStatus.EXECUTED, svc.find(req.getId()).orElseThrow().getExecutionStatus());
    }

    @Test
    void executionLease_shouldFenceStaleWorker_afterCrashRecovery() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        ApprovalRequest req = new ApprovalRequest("AP-1", ApprovalType.REFUND, "s1", "O1",
            "299.00", "测试", 1L);
        store.save(req);
        assertTrue(store.decide(req.getId(), ApprovalStatus.APPROVED, "alice", null, 2L));
        assertTrue(store.claimExecution(req.getId(), 3, 100L, "worker-1"));

        assertEquals(1, store.recoverStuckExecutions(101L));
        assertTrue(store.claimExecution(req.getId(), 3, 200L, "worker-2"));

        assertFalse(store.completeExecution(req.getId(), "worker-1", true, null));
        assertTrue(store.completeExecution(req.getId(), "worker-2", true, null));
        assertEquals(ExecutionStatus.EXECUTED, store.find(req.getId()).orElseThrow().getExecutionStatus());
    }

    @Test
    void retry_shouldKeepStableIdempotencyKey_andRotateFencingToken() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        AtomicReference<ApprovalExecutionContext> first = new AtomicReference<>();
        AtomicReference<ApprovalExecutionContext> second = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        ApprovalExecutionHandler handler = context -> {
            if (attempts.incrementAndGet() == 1) {
                first.set(context);
                throw new IllegalStateException("temporary failure");
            }
            second.set(context);
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<ApprovalExecutionHandler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(handler);
        PendingApprovalService svc = new PendingApprovalService(store, provider);
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");

        svc.approve(req.getId(), "alice");
        svc.retryExecutionFailures(3);

        assertEquals(req.getId(), first.get().idempotencyKey());
        assertEquals(first.get().idempotencyKey(), second.get().idempotencyKey());
        assertFalse(first.get().fencingToken().equals(second.get().fencingToken()));
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
    void retryDisabled_shouldStillRecoverExpiredExecutingState() {
        InMemoryApprovalStore store = new InMemoryApprovalStore();
        PendingApprovalService svc = new PendingApprovalService(store);
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "299.00", "测试");
        assertTrue(store.decide(req.getId(), ApprovalStatus.APPROVED, "alice", null, 1L));
        assertTrue(store.claimExecution(req.getId(), 1, 1L, "crashed-worker"));

        assertEquals(0, svc.retryExecutionFailures(1));

        assertEquals(ExecutionStatus.EXECUTE_FAILED,
            svc.find(req.getId()).orElseThrow().getExecutionStatus());
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
