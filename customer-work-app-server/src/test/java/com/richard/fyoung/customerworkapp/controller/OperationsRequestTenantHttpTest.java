package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.FactRecord;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.diagnostics.DiagnosticService;
import com.richard.fyoung.customerwork.observability.AuditQuery;
import com.richard.fyoung.customerwork.observability.AuditRecord;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.analytics.BusinessAnalyticsService;
import com.richard.fyoung.customerwork.safety.security.ApiKeyAuthWebFilter;
import com.richard.fyoung.customerwork.safety.security.ApprovalAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实本地 HTTP + 原有内存审批存储，覆盖租户插件关闭时的运营入口。 */
class OperationsRequestTenantHttpTest {
    private static final String SESSION = "tenant-B:shared-session";
    private final CustomerWorkProperties properties = new CustomerWorkProperties();
    private final InMemoryApprovalStore store = new InMemoryApprovalStore();
    private final PendingApprovalService approvals = new PendingApprovalService(store);
    private final SlotFillingService slots = new SlotFillingService();
    private final FactLog facts = mock(FactLog.class);
    private final List<DecisionAudit> decisionAudits = new CopyOnWriteArrayList<>();
    private final List<String> auditReadTenants = new CopyOnWriteArrayList<>();
    private AnnotationConfigReactiveWebApplicationContext context;
    private DisposableServer server;
    private WebTestClient client;

    @AfterEach
    void closeServer() {
        if (server != null) server.disposeNow();
        if (context != null) context.close();
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"approval", "refund", "analytics", "diagnostic"})
    void anonymousLocalModeRetainsDefaultTenantOperations(String operation) {
        seed(TenantContext.DEFAULT, "AP-default");
        seed("tenant-B", "AP-foreign");
        startServer();
        assertOperation(operation, null, "AP-default", TenantContext.DEFAULT);
        assertNull(TenantContext.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approval", "refund", "analytics", "diagnostic"})
    void apiKeyTenantSurvivesHttpAndAsyncDispatchWithoutTenantPlugin(String operation) {
        enableApiKey();
        seed("tenant-A", "AP-owned");
        seed("tenant-B", "AP-foreign");
        startServer();
        assertOperation(operation, "tenant-a-key", "AP-owned", "tenant-A");
        assertNull(TenantContext.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approval", "refund", "analytics", "diagnostic"})
    void tenantModeWithoutAuthenticatedPrincipalRejectsInsteadOfUsingDefault(String operation) {
        properties.getTenant().setEnabled(true);
        seed(TenantContext.DEFAULT, "AP-default");
        startServer();
        switch (operation) {
            case "approval" -> get("/api/customer/approvals", null).expectStatus().isUnauthorized();
            case "refund" -> postForm(null, "123456").expectStatus().isUnauthorized();
            case "analytics" -> get("/api/customer/analytics/business?tenantId=default", null)
                .expectStatus().isUnauthorized();
            case "diagnostic" -> get("/api/customer/diagnostics/session/default:session", null)
                .expectStatus().isUnauthorized();
            default -> throw new IllegalArgumentException(operation);
        }
        assertEquals(1, TenantContext.callWith(TenantContext.DEFAULT, () -> store.findAll().size()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "deny"})
    void decisionsKeepOperatorAuthorizationAndAuditInsideAuthenticatedTenant(String decision) {
        enableApiKey();
        properties.getSecurity().getApprovalAuth().setEnabled(true);
        properties.getSecurity().getApprovalAuth().getOperators().put("review-token", "verified-reviewer");
        seed("tenant-A", "AP-shared");
        seed("tenant-B", "AP-shared");
        seed("tenant-B", "AP-foreign-only");
        var executions = new CopyOnWriteArrayList<String>();
        // 本测试执行器仅记录租户，不连接任何订单或资金后端。
        approvals.onApprove(request -> executions.add(TenantContext.require()));
        startServer();

        postDecision("AP-shared", decision, null).expectStatus().isUnauthorized();
        get("/api/customer/approvals/AP-foreign-only", "tenant-a-key").expectStatus().isNotFound();
        postDecision("AP-foreign-only", decision, "review-token").expectStatus().isNotFound();
        postDecision("AP-shared", decision, "review-token").expectStatus().isOk()
            .expectBody().jsonPath("$.operator").isEqualTo("verified-reviewer")
            .jsonPath("$.status").isEqualTo("approve".equals(decision) ? "APPROVED" : "DENIED");
        postDecision("AP-shared", decision, "review-token").expectStatus().isEqualTo(409);

        assertEquals(ApprovalStatus.PENDING,
            TenantContext.callWith("tenant-B", () -> store.find("AP-shared").orElseThrow().getStatus()));
        assertEquals(1, decisionAudits.size());
        assertEquals("tenant-A", decisionAudits.get(0).tenant());
        assertEquals("verified-reviewer", decisionAudits.get(0).fields().get("operator"));
        assertEquals("approval-decision", decisionAudits.get(0).type());
        assertEquals("approve".equals(decision) ? List.of("tenant-A") : List.of(), executions);
        get("/api/customer/approvals?status=not-a-status", "tenant-a-key").expectStatus().isBadRequest();
        get("/api/customer/approvals?status=pending", "tenant-a-key").expectStatus().isOk()
            .expectBody().jsonPath("$.length()").isEqualTo(0);
        get("/api/customer/approvals/AP-shared", "tenant-a-key").expectStatus().isOk();
    }

    @Test
    void analyticsQualityParameterCannotSelectAnotherTenant() {
        enableApiKey();
        seed("tenant-A", "AP-owned");
        seed("tenant-B", "AP-foreign");
        var qualityTenants = new CopyOnWriteArrayList<String>();
        when(facts.readRecords(anyString())).thenAnswer(invocation -> {
            String scope = invocation.getArgument(0);
            qualityTenants.add(scope);
            assertEquals("tenant-A", TenantContext.require());
            return List.of(new FactRecord(10, scope, "{\"type\":\"quality-failure\",\"score\":50}"));
        });
        startServer();
        String path = "/api/customer/analytics/business?windowStartMs=0&windowEndMs=100";
        get(path + "&tenantId=tenant-B", "tenant-a-key").expectStatus().isForbidden();
        assertTrue(qualityTenants.isEmpty());
        get(path, "tenant-a-key").expectStatus().isOk().expectBody()
            .jsonPath("$.approval.totalInWindow").isEqualTo(1)
            .jsonPath("$.quality.tenantId").isEmpty();
        get(path + "&tenantId=tenant-a", "tenant-a-key").expectStatus().isOk().expectBody()
            .jsonPath("$.approval.totalInWindow").isEqualTo(1)
            .jsonPath("$.quality.tenantId").isEqualTo("tenant-A");
        assertEquals(List.of("tenant-A"), qualityTenants);
    }

    @Test
    void diagnosticAuditUsesApiKeyTenantDespiteConflictingSessionPrefix() {
        enableApiKey();
        seed("tenant-A", "AP-owned");
        startServer();
        get("/api/customer/diagnostics/session/" + SESSION + "/audit", "tenant-a-key")
            .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(1);
        assertEquals(List.of("tenant-A"), auditReadTenants);
    }

    private void assertOperation(String operation, String key, String approvalId, String tenant) {
        switch (operation) {
            case "approval" -> get("/api/customer/approvals", key).expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(approvalId);
            case "refund" -> {
                postForm(key, "123456").expectStatus().isOk()
                    .expectBody().jsonPath("$.complete").isEqualTo(false);
                postForm(key, "质量问题").expectStatus().isOk()
                    .expectBody().jsonPath("$.complete").isEqualTo(true)
                    .jsonPath("$.approvalId").isNotEmpty();
                assertEquals(2, TenantContext.callWith(tenant, () -> store.findAll().size()));
                assertEquals(1, TenantContext.callWith("tenant-B", () -> store.findAll().size()));
            }
            case "analytics" -> get("/api/customer/analytics/business?windowStartMs=0&windowEndMs=100", key)
                .expectStatus().isOk().expectBody().jsonPath("$.approval.totalInWindow").isEqualTo(1);
            case "diagnostic" -> get("/api/customer/diagnostics/session/" + SESSION, key)
                .expectStatus().isOk().expectBody().jsonPath("$.tenantId").isEqualTo(tenant)
                .jsonPath("$.approvals.length()").isEqualTo(1)
                .jsonPath("$.approvals[0].id").isEqualTo(approvalId)
                .jsonPath("$.degradedSources.length()").isEqualTo(0);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private WebTestClient.ResponseSpec get(String path, String key) {
        return client.get().uri(path).headers(headers -> {
            if (key != null) headers.set("X-API-Key", key);
        }).exchange();
    }

    private WebTestClient.ResponseSpec postForm(String key, String message) {
        return client.post().uri("/api/customer/forms/refund").headers(headers -> {
            if (key != null) headers.set("X-API-Key", key);
        }).contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("sessionId", SESSION, "message", message)).exchange();
    }

    private WebTestClient.ResponseSpec postDecision(String id, String decision, String token) {
        return client.post().uri("/api/customer/approvals/" + id + "/" + decision + "?operator=spoofed")
            .header("X-API-Key", "tenant-a-key").headers(headers -> {
                if (token != null) headers.set("X-Approval-Token", token);
            }).exchange();
    }

    private void enableApiKey() {
        properties.getSecurity().getAuth().setEnabled(true);
        properties.getSecurity().getAuth().getTenantKeys().put("tenant-a-key", "tenant-A");
    }

    private void seed(String tenant, String id) {
        TenantContext.runWith(tenant, () -> store.save(new ApprovalRequest(
            id, ApprovalType.REFUND, SESSION, "123456", "20.00", "测试原因", 10)));
    }

    private void startServer() {
        context = new AnnotationConfigReactiveWebApplicationContext();
        context.registerBean(CustomerWorkProperties.class, () -> properties);
        context.registerBean(PendingApprovalService.class, () -> approvals);
        context.registerBean(SlotFillingService.class, () -> slots);
        context.registerBean(AuditSink.class, () -> (type, fields) -> decisionAudits.add(
            new DecisionAudit(TenantContext.require(), type, Map.copyOf(fields))));
        context.registerBean(AuditQuery.class, () -> (sessionId, limit) -> {
            auditReadTenants.add(TenantContext.require());
            return List.of(new AuditRecord("test", sessionId, "{}", 1));
        });
        context.registerBean(BusinessAnalyticsService.class, () -> new BusinessAnalyticsService(
            approvals, new HandoffService(), facts));
        context.registerBean(DiagnosticService.class, () -> new DiagnosticService(
            mock(SessionStateManager.class), new DialogStageService(), slots, approvals,
            facts, new TenantResolver(properties), context.getBeanProvider(AuditQuery.class)));
        context.registerBean(ApiKeyAuthWebFilter.class, () -> new ApiKeyAuthWebFilter(properties));
        context.registerBean(ApprovalAuthWebFilter.class, () -> new ApprovalAuthWebFilter(properties));
        context.register(WebConfiguration.class, ApprovalController.class, RefundFormController.class,
            BusinessAnalyticsController.class, DiagnosticController.class);
        context.scan("com.richard.fyoung.customerworkapp.web");
        context.refresh();
        assertEquals(1, context.getBeansOfType(ApiKeyAuthWebFilter.class).size());
        assertEquals(1, context.getBeansOfType(ApprovalAuthWebFilter.class).size());
        var handler = WebHttpHandlerBuilder.applicationContext(context).build();
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle(new ReactorHttpHandlerAdapter(handler)).bindNow();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + server.port())
            .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration { }

    private record DecisionAudit(String tenant, String type, Map<String, Object> fields) { }
}
