package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款工具与审批闭环的接线单测：注入 PendingApprovalService 后，submitRefund 登记待审单且回执带审批单号；
 * 未注入时退化为纯工单文案（不登记）。
 * @author owlzhangfq@gmail.com
 */
class AfterSalesToolsApprovalTest {

    @Test
    void submitRefund_shouldRegisterPendingApproval_whenServicePresent() {
        PendingApprovalService svc = new PendingApprovalService();
        AfterSalesTools tools = new AfterSalesTools(new MockAfterSalesBackend(), svc, "session-real-1");

        String reply = tools.submitRefund("O1", "299.00", "七天无理由").block(Duration.ofSeconds(2));

        assertTrue(reply.contains("审批单号 AP-"), "回执应包含审批单号: " + reply);
        assertEquals(1, svc.listByStatus(ApprovalStatus.PENDING).size(), "应登记一张待审单");
        assertEquals("session-real-1",
            svc.listByStatus(ApprovalStatus.PENDING).get(0).getSessionId());
    }

    @Test
    void submitRefund_shouldDegradeGracefully_whenServiceAbsent() {
        AfterSalesTools tools = new AfterSalesTools(new MockAfterSalesBackend());
        String reply = tools.submitRefund("O1", "299.00", "七天无理由").block(Duration.ofSeconds(2));
        assertTrue(reply.contains("退款工单"), "未注入审批服务时应仍返回工单文案: " + reply);
    }

    @Test
    void submitRefund_shouldFailFast_whenApprovalHasNoRealSession() {
        AfterSalesTools tools = new AfterSalesTools(
            new MockAfterSalesBackend(), new PendingApprovalService());

        assertThrows(IllegalStateException.class,
            () -> tools.submitRefund("O1", "299.00", "七天无理由").block(Duration.ofSeconds(2)));
    }
}
