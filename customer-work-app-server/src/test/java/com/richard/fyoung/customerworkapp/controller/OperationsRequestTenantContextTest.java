package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.safety.security.ApiKeyPrincipal;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerworkapp.web.ApiRequestTenant;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.scheduler.Schedulers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 请求身份在组装时冻结，实际工作线程与调用方在成功、审计异常后均恢复原租户。 */
class OperationsRequestTenantContextTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capturedTenantCoversDecisionAndAuditAndRestoresWorker(boolean auditFails) throws Exception {
        var properties = new CustomerWorkProperties();
        properties.getSecurity().getAuth().setEnabled(true);
        var store = new InMemoryApprovalStore();
        var service = new PendingApprovalService(store);
        TenantContext.runWith("tenant-A", () -> store.save(new ApprovalRequest(
            "AP-owned", ApprovalType.REFUND, "session", "123456", "10", "reason", 1)));
        var auditTenant = new AtomicReference<String>();
        var signalTenant = new AtomicReference<String>();
        var afterActionTenant = new AtomicReference<String>();
        var afterScopeTenant = new AtomicReference<String>();
        var originalWorkerTenant = new AtomicReference<String>();
        var completed = new CountDownLatch(1);
        var controller = new ApprovalController(service, (type, fields) -> {
            auditTenant.set(TenantContext.require());
            if (auditFails) throw new IllegalArgumentException("audit unavailable");
        }, new ApiRequestTenant(properties));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/customer/approvals/AP-owned/deny"));
        exchange.getAttributes().put(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE,
            new ApiKeyPrincipal("key-a", "tenant-A", 1, Set.of("*")));
        var pending = TenantContext.callWith("caller-before", () -> {
            var result = controller.deny("AP-owned", "reviewer", null, exchange);
            assertEquals("caller-before", TenantContext.require());
            return result;
        });
        exchange.getAttributes().put(ApiKeyPrincipal.EXCHANGE_ATTRIBUTE,
            new ApiKeyPrincipal("late-key-b", "tenant-B", 1, Set.of("*")));

        String hook = "operations-request-tenant-context";
        Schedulers.onScheduleHook(hook, action -> () -> {
            originalWorkerTenant.set(TenantContext.get());
            try {
                TenantContext.runWith("worker-before", () -> {
                    action.run();
                    afterActionTenant.set(TenantContext.get());
                });
                afterScopeTenant.set(TenantContext.get());
            } finally {
                completed.countDown();
            }
        });
        try {
            TenantContext.runWith("subscriber-before", () -> {
                var observed = pending.doOnSuccess(result -> signalTenant.set(TenantContext.get()))
                    .doOnError(error -> signalTenant.set(TenantContext.get()));
                if (auditFails) {
                    assertThrows(IllegalArgumentException.class, () -> observed.block(Duration.ofSeconds(5)));
                } else {
                    assertEquals("AP-owned", observed.block(Duration.ofSeconds(5)).getId());
                }
                assertEquals("subscriber-before", TenantContext.require());
            });
            assertTrue(completed.await(5, TimeUnit.SECONDS), "必须等真实工作线程结束再检查上下文恢复");
            assertEquals("tenant-A", auditTenant.get());
            assertEquals("worker-before", signalTenant.get());
            assertEquals("worker-before", afterActionTenant.get());
            assertEquals(originalWorkerTenant.get(), afterScopeTenant.get());
            assertNull(TenantContext.get());
        } finally {
            Schedulers.resetOnScheduleHook(hook);
            TenantContext.clear();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void requiredIdentityCannotFallBackToStaleThreadOrRequestTenant(boolean auth, boolean tenant) {
        var properties = new CustomerWorkProperties();
        properties.getSecurity().getAuth().setEnabled(auth);
        properties.getTenant().setEnabled(tenant);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
            .get("/api/customer/approvals?tenantId=default&sessionId=default:session")
            .header("X-Tenant-Id", "default"));
        TenantContext.runWith("stale-tenant", () -> {
            var error = assertThrows(ResponseStatusException.class,
                () -> new ApiRequestTenant(properties).require(exchange));
            assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
            assertEquals("stale-tenant", TenantContext.require());
        });
        assertNull(TenantContext.get());
    }
}
