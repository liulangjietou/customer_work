package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.InMemoryApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.core.agent.AgentGovernanceAssembler;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.diagnostics.DiagnosticService;
import com.richard.fyoung.customerwork.observability.AuditQuery;
import com.richard.fyoung.customerwork.observability.AuditRecord;
import com.richard.fyoung.customerwork.safety.security.ApiKeyAuthWebFilter;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.reactive.context.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实运行上下文生成写入键，真实 StateStore 和 HTTP 读取，避免模拟状态探测掩盖命名空间回退。 */
class DiagnosticStateNamespaceHttpTest {
    private static final String SCHEDULE_HOOK = "diagnostic-state-namespace";
    private final CustomerWorkProperties properties = new CustomerWorkProperties();
    private final TenantResolver tenantResolver = new TenantResolver(properties);
    private final AgentGovernanceAssembler governance = new AgentGovernanceAssembler(
        properties, tenantResolver, null, null);
    private final InMemoryAgentStateStore states = new InMemoryAgentStateStore();
    private final SessionStateManager stateManager = new SessionStateManager(states);
    private final InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
    private final PendingApprovalService approvals = new PendingApprovalService(approvalStore);
    private final FactLog facts = mock(FactLog.class);
    private final List<String> auditTenants = new CopyOnWriteArrayList<>();
    private final List<String> factTenants = new CopyOnWriteArrayList<>();
    private AnnotationConfigReactiveWebApplicationContext application;
    private DisposableServer server;
    private WebTestClient client;

    @AfterEach
    void close() {
        if (server != null) server.disposeNow();
        if (application != null) application.close();
        states.close();
        Schedulers.resetOnScheduleHook(SCHEDULE_HOOK);
        TenantContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice:conv", "legacy-session"})
    void anonymousLocalDiagnosisKeepsExistingStateNamespaceButReadsDefaultBusinessData(String sessionId) {
        String stateUserId = saveThroughRuntimeContext(sessionId, null);
        assertTrue(stateManager.exists(stateUserId, sessionId));
        assertFalse(stateManager.exists(TenantContext.DEFAULT, sessionId));
        seedApproval(TenantContext.DEFAULT, "AP-default", sessionId);
        seedApproval(stateUserId, "AP-foreign", sessionId);
        startServer(TenantContext.DEFAULT, sessionId);

        get(sessionId, null).expectStatus().isOk().expectBody()
            .jsonPath("$.stateExists").isEqualTo(true)
            .jsonPath("$.tenantId").isEqualTo(TenantContext.DEFAULT)
            .jsonPath("$.approvals.length()").isEqualTo(1)
            .jsonPath("$.approvals[0].id").isEqualTo("AP-default")
            .jsonPath("$.degradedSources.length()").isEqualTo(0);
        assertEquals(List.of(TenantContext.DEFAULT), auditTenants);
        assertEquals(List.of(TenantContext.DEFAULT), factTenants);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void apiKeyDiagnosisUsesOnlyItsTrustedStateNamespaceRegardlessOfSessionPrefix(boolean tenantEnabled) {
        String sessionId = "tenant-B:shared";
        properties.getSecurity().getAuth().setEnabled(true);
        properties.getSecurity().getAuth().getTenantKeys().put("tenant-a-key", "tenant-A");
        properties.getTenant().setEnabled(tenantEnabled);
        String legacyUserId = saveThroughRuntimeContext(sessionId, null);
        assertEquals("tenant-B", legacyUserId);
        seedApproval("tenant-A", "AP-owned", sessionId);
        seedApproval("tenant-B", "AP-foreign", sessionId);
        startServer("tenant-A", sessionId);

        get(sessionId, "tenant-a-key").expectStatus().isOk().expectBody()
            .jsonPath("$.stateExists").isEqualTo(false)
            .jsonPath("$.tenantId").isEqualTo("tenant-A")
            .jsonPath("$.approvals[0].id").isEqualTo("AP-owned");
        assertEquals("tenant-A", saveThroughRuntimeContext(sessionId, "tenant-A"));
        get(sessionId, "tenant-a-key").expectStatus().isOk().expectBody()
            .jsonPath("$.stateExists").isEqualTo(true)
            .jsonPath("$.approvals.length()").isEqualTo(1);
        assertEquals(List.of("tenant-A", "tenant-A"), auditTenants);
        assertEquals(List.of("tenant-A", "tenant-A"), factTenants);
    }

    @Test
    void tenantModeWithoutIdentityCannotUseLegacyStateCompatibility() {
        String sessionId = "alice:conv";
        properties.getTenant().setEnabled(true);
        saveThroughRuntimeContext(sessionId, null);
        startServer(TenantContext.DEFAULT, sessionId);
        get(sessionId, null).expectStatus().isUnauthorized();
        assertTrue(auditTenants.isEmpty());
        assertTrue(factTenants.isEmpty());
    }

    /** 与实际 Agent 调用共用 contextFor，不手写假定的 userId 作为保存键。 */
    private String saveThroughRuntimeContext(String sessionId, String requestTenant) {
        var runtime = TenantContext.callWith(requestTenant, () -> governance.contextFor(sessionId));
        states.save(runtime.getUserId(), runtime.getSessionId(), "history", Msg.builder()
            .role(MsgRole.USER).name("user").content(TextBlock.builder().text("历史会话").build()).build());
        return runtime.getUserId();
    }

    private void seedApproval(String tenantId, String id, String sessionId) {
        TenantContext.runWith(tenantId, () -> approvalStore.save(new ApprovalRequest(
            id, ApprovalType.REFUND, sessionId, "ORDER-1", "10.00", "测试", 1)));
    }

    private WebTestClient.ResponseSpec get(String sessionId, String key) {
        return client.get().uri("/api/customer/diagnostics/session/" + sessionId + "?tenantId=stale-worker")
            .header("X-Tenant-Id", "stale-worker").headers(headers -> {
                if (key != null) headers.set("X-API-Key", key);
            }).exchange();
    }

    private void startServer(String expectedTenant, String sessionId) {
        when(facts.read(anyString())).thenAnswer(invocation -> {
            String tenantId = invocation.getArgument(0);
            assertEquals(expectedTenant, tenantId);
            assertEquals(expectedTenant, TenantContext.require());
            factTenants.add(tenantId);
            return List.of("quality:" + sessionId);
        });
        application = new AnnotationConfigReactiveWebApplicationContext();
        application.registerBean(CustomerWorkProperties.class, () -> properties);
        application.registerBean(AuditQuery.class, () -> (id, limit) -> {
            assertEquals(expectedTenant, TenantContext.require());
            auditTenants.add(TenantContext.require());
            return List.of(new AuditRecord("test", id, "{}", 1));
        });
        application.registerBean(DiagnosticService.class, () -> new DiagnosticService(
            stateManager, new DialogStageService(), new SlotFillingService(), approvals,
            facts, tenantResolver, application.getBeanProvider(AuditQuery.class)));
        application.registerBean(ApiKeyAuthWebFilter.class, () -> new ApiKeyAuthWebFilter(properties));
        application.register(WebConfiguration.class, DiagnosticController.class);
        application.scan("com.richard.fyoung.customerworkapp.web");
        application.refresh();
        // 主动留下另一租户的调度线程上下文，验证入口快照而非线程残留决定业务作用域。
        Schedulers.onScheduleHook(SCHEDULE_HOOK,
            action -> () -> TenantContext.runWith("stale-worker", action));
        var handler = WebHttpHandlerBuilder.applicationContext(application).build();
        server = HttpServer.create().host("127.0.0.1").port(0)
            .handle(new ReactorHttpHandlerAdapter(handler)).bindNow();
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + server.port())
            .responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class WebConfiguration { }
}
