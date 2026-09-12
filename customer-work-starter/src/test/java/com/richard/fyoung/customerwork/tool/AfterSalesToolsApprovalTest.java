package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.tenant.TenantContextMissingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 退款工具与审批闭环的接线单测：注入 PendingApprovalService 后，submitRefund 登记待审单且回执带审批单号；
 * 未注入时退化为纯工单文案（不登记）。
 * @author owlzhangfq@gmail.com
 */
class AfterSalesToolsApprovalTest {

    @BeforeEach
    void bindTenant() { TenantContext.set(TenantContext.DEFAULT); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }


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
    void submitRefundCapturesTenantBeforeBackendSwitchesThread() {
        TenantContext.set("tenant-A");
        PendingApprovalService service = new PendingApprovalService();
        AfterSalesTools tools = new AfterSalesTools(new MockAfterSalesBackend(), service, "session-A");
        assertDoesNotThrow(() -> tools.submitRefund("O1", "9.00", "测试").block(Duration.ofSeconds(2)));
        assertEquals(1, service.list().size());
        assertTrue(TenantContext.callWith("tenant-B", service::list).isEmpty());
    }

    @Test
    void backendCompletesAfterToolReturnsAndBothThreadsKeepTheirOwnContext() throws Exception {
        var backend = mock(AfterSalesBackend.class);
        var backendReply = new CompletableFuture<String>();
        when(backend.submitRefund("O1", "9.00", "测试")).thenReturn(Mono.fromFuture(backendReply));
        var service = new PendingApprovalService();
        var tools = new AfterSalesTools(backend, service, "session-A");
        TenantContext.set("tenant-A");
        Mono<String> reply = tools.submitRefund("O1", "9.00", "测试");
        assertEquals("tenant-A", TenantContext.get());
        TenantContext.set("caller-after");
        var result = reply.toFuture();
        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                TenantContext.runWith("worker-before", () -> {
                    assertTrue(backendReply.complete("退款工单"));
                    assertEquals("worker-before", TenantContext.get(), "回调登记审批后应恢复工作线程原身份");
                });
                assertNull(TenantContext.get());
            }).get(3, TimeUnit.SECONDS);
            assertTrue(result.get(3, TimeUnit.SECONDS).contains("审批单号"));
            assertEquals("caller-after", TenantContext.get());
            assertEquals(1, TenantContext.callWith("tenant-A", service::list).size());
            assertTrue(TenantContext.callWith("worker-before", service::list).isEmpty());
            assertTrue(service.list().isEmpty());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void missingTenantRejectsBeforeSubmittingToBackend() {
        var backend = mock(AfterSalesBackend.class);
        var tools = new AfterSalesTools(backend, new PendingApprovalService(), "session-A");
        TenantContext.clear();
        assertThrows(TenantContextMissingException.class, () -> tools.submitRefund("O1", "9.00", "测试"));
        verifyNoInteractions(backend);
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
