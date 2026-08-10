package com.richard.fyoung.customerwork.capability.approval;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 审批超时巡检器单测：超时自动拒绝 / 超时升级告警 / 未超时不处理。
 * @author owlzhangfq@gmail.com
 */
class ApprovalTimeoutSchedulerTest {

    @Test
    void shouldAutoDenyTimedOutApproval_whenActionIsDeny() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setTimeoutSeconds(1);
        props.getHumanApproval().setTimeoutAction("deny");

        PendingApprovalService svc = new PendingApprovalService();
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "100", "test");

        // 模拟超时：修改创建时间为 2 秒前
        ApprovalRequest timedOut = new ApprovalRequest(
            req.getId(), ApprovalType.REFUND, "s1", "O1", "100", "test",
            System.currentTimeMillis() - 2000);
        // 重新保存到 store
        svc.getStore().delete(req.getId());
        svc.getStore().save(timedOut);

        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(props, svc);
        scheduler.runTimeoutCheck();

        ApprovalRequest stored = svc.find(req.getId()).orElseThrow();
        assertEquals(ApprovalStatus.DENIED, stored.getStatus());
        assertEquals("system-timeout", stored.getOperator());
    }

    @Test
    void shouldNotModifyApproval_whenActionIsEscalate() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setTimeoutSeconds(1);
        props.getHumanApproval().setTimeoutAction("escalate");

        PendingApprovalService svc = new PendingApprovalService();
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "100", "test");

        // 模拟超时
        ApprovalRequest timedOut = new ApprovalRequest(
            req.getId(), ApprovalType.REFUND, "s1", "O1", "100", "test",
            System.currentTimeMillis() - 2000);
        svc.getStore().delete(req.getId());
        svc.getStore().save(timedOut);

        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(props, svc);
        scheduler.runTimeoutCheck();

        // escalate 模式：保持 PENDING
        ApprovalRequest stored = svc.find(req.getId()).orElseThrow();
        assertEquals(ApprovalStatus.PENDING, stored.getStatus());
    }

    @Test
    void shouldNotProcess_whenTimeoutDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        // timeoutSeconds 默认 0 = 禁用
        PendingApprovalService svc = new PendingApprovalService();
        svc.submit(ApprovalType.REFUND, "s1", "O1", "100", "test");

        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(props, svc);
        scheduler.runTimeoutCheck();

        // 禁用时不处理任何审批单
        assertEquals(1, svc.listByStatus(ApprovalStatus.PENDING).size());
    }

    @Test
    void shouldNotProcessRecentApprovals() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setTimeoutSeconds(60);
        props.getHumanApproval().setTimeoutAction("deny");

        PendingApprovalService svc = new PendingApprovalService();
        svc.submit(ApprovalType.REFUND, "s1", "O1", "100", "test");

        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(props, svc);
        scheduler.runTimeoutCheck();

        // 刚创建的审批单未超时，不应被处理
        assertEquals(1, svc.listByStatus(ApprovalStatus.PENDING).size());
    }

    @Test
    void retryExecutionFailures_shouldDelegateToService() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHumanApproval().setMaxExecutionRetryAttempts(3);

        PendingApprovalService svc = new PendingApprovalService();
        svc.onApprove(r -> {
            throw new RuntimeException("boom");
        });
        ApprovalRequest req = svc.submit(ApprovalType.REFUND, "s1", "O1", "100", "test");
        svc.approve(req.getId(), "alice");
        assertEquals(ExecutionStatus.EXECUTE_FAILED, svc.find(req.getId()).orElseThrow().getExecutionStatus());

        ApprovalTimeoutScheduler scheduler = new ApprovalTimeoutScheduler(props, svc);
        scheduler.retryExecutionFailures();

        // 重试后 attempts 累加（回调依旧抛异常，状态仍是 EXECUTE_FAILED，但已发起过重试）
        assertEquals(2, svc.find(req.getId()).orElseThrow().getExecutionAttempts());
    }
}
