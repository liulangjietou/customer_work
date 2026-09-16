package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** 经过真实原生 Toolkit 调度；方法调用前的线程切换与后端回调切换是两处独立边界。 */
class AfterSalesToolsNativeContextTest {
    private static final String SESSION = "session-native-refund";
    private static final Map<String, Object> INPUT = Map.of("orderId", "O1", "amount", "9.00", "reason", "测试");
    private static final AgentInvocationIdentity TRUSTED_IDENTITY =
        new AgentInvocationIdentity("tenant-native", QuotaSubjectType.USER, "trusted-user", true, 23L)
            .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, SESSION, "customer-service");

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @ParameterizedTest(name = "{0}: freezes identity and restores both contexts")
    @MethodSource("nativeTools")
    void nativeToolsPassTheCompleteTrustedIdentityAndRestoreContextsAtEveryBoundary(ToolCase tool) throws Exception {
        var backendCall = new AtomicReference<BackendCall>();
        var backendReply = new CompletableFuture<String>();
        var subscribed = new CountDownLatch(1);
        var backend = recordingBackend(backendCall,
            () -> Mono.fromFuture(backendReply).doOnSubscribe(ignored -> subscribed.countDown()));
        var approvalScope = new AtomicReference<Scope>();
        var service = spy(new PendingApprovalService());
        doAnswer(invocation -> {
            approvalScope.set(Scope.capture());
            return invocation.callRealMethod();
        }).when(service).submit(any(), anyString(), anyString(), anyString(), anyString());
        var toolkit = toolkit(new AfterSalesTools(backend, service, SESSION));
        var callerBefore = Scope.forTenant("caller-before");
        callerBefore.install();

        var reply = toolkit.callTool(call(tool, trustedRuntimeContext()));
        assertEquals(callerBefore, Scope.capture(), "组装原生调用后不能留下工具身份");

        var callerAfter = Scope.forTenant("caller-after");
        callerAfter.install();
        var result = reply.toFuture();
        assertEquals(callerAfter, Scope.capture(), "订阅不能改写调用线程的当前身份");
        assertTrue(subscribed.await(3, TimeUnit.SECONDS), "真实 Toolkit 必须已调用并订阅后端");

        var worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                var workerBefore = Scope.forTenant("worker-before");
                workerBefore.install();
                try {
                    assertTrue(backendReply.complete("backend-result"));
                    assertEquals(workerBefore, Scope.capture(), "后端完成及审批回调后必须恢复工作线程身份");
                } finally {
                    TenantContext.clear();
                    AgentInvocationIdentityContext.clear();
                }
                assertEquals(new Scope(null, null), Scope.capture());
            }).get(3, TimeUnit.SECONDS);

            var output = result.get(3, TimeUnit.SECONDS).getOutput().toString();
            assertTrue(output.contains("backend-result"), () -> "Native tool output: " + output);
            assertEquals(callerAfter, Scope.capture());
            int expectedApprovals = "submitRefund".equals(tool.name()) ? 1 : 0;
            assertEquals(expectedApprovals, TenantContext.callWith(TRUSTED_IDENTITY.tenantId(), service::list).size());
            assertTrue(TenantContext.callWith("worker-before", service::list).isEmpty());
            assertTrue(service.list().isEmpty());
            if (expectedApprovals == 1) {
                assertEquals(new Scope(TRUSTED_IDENTITY.tenantId(), TRUSTED_IDENTITY), approvalScope.get(),
                    "审批回调也必须使用入口的完整身份，不能继承完成线程的主体");
            }
            assertEquals(new BackendCall(tool.name(), new Scope(TRUSTED_IDENTITY.tenantId(), TRUSTED_IDENTITY)),
                backendCall.get(), "后端必须在方法调用时取得完整可信身份，不能继承残留 ThreadLocal 或 RuntimeContext.userId");
        } finally {
            worker.shutdownNow();
        }
    }

    @ParameterizedTest(name = "{0}: missing native identity does not inherit caller")
    @MethodSource("nativeTools")
    void absentNativeIdentityClearsStaleContextsWhileKeepingDemonstrationBackendUsable(ToolCase tool) {
        var backendCall = new AtomicReference<BackendCall>();
        var backend = recordingBackend(backendCall, () -> Mono.just("demonstration-result"));
        var toolkit = toolkit(new AfterSalesTools(backend));
        var caller = Scope.forTenant("caller-before");
        caller.install();
        var context = RuntimeContext.builder().userId(TRUSTED_IDENTITY.subjectId()).sessionId(SESSION).build();

        var result = toolkit.callTool(call(tool, context)).block(Duration.ofSeconds(5));

        assertTrue(result.getOutput().toString().contains("demonstration-result"));
        assertEquals(caller, Scope.capture());
        assertEquals(new BackendCall(tool.name(), new Scope(null, null)), backendCall.get(),
            "缺少可信快照时必须清空两个上下文，不能把 RuntimeContext.userId 或调用线程主体当成当前用户");
    }

    @ParameterizedTest(name = "{0}: model-visible business parameters")
    @MethodSource("nativeTools")
    void nativeToolSchemasContainOnlyTheirExistingBusinessArguments(ToolCase tool) {
        var toolkit = toolkit(new AfterSalesTools(new MockAfterSalesBackend()));
        Map<?, ?> parameters = toolkit.getTool(tool.name()).getParameters();
        assertEquals(tool.input().keySet(), ((Map<?, ?>) parameters.get("properties")).keySet());
    }

    @ParameterizedTest(name = "refund backend delayed failure={0}: no approval")
    @ValueSource(booleans = {false, true})
    void backendFailureDoesNotRegisterApprovalAndRestoresTheCaller(boolean delayedFailure) {
        var backendCall = new AtomicReference<BackendCall>();
        var backend = recordingBackend(backendCall, () -> {
            var failure = new IllegalStateException("backend unavailable");
            if (delayedFailure) {
                return Mono.error(failure);
            }
            throw failure;
        });
        var service = new PendingApprovalService();
        var toolkit = toolkit(new AfterSalesTools(backend, service, SESSION));
        var caller = Scope.forTenant("caller-before");
        caller.install();

        var result = toolkit.callTool(call(trustedRuntimeContext())).block(Duration.ofSeconds(5));

        assertTrue(result.getOutput().toString().contains("backend unavailable"));
        assertFalse(result.getOutput().toString().contains("审批单号"));
        assertEquals("submitRefund", backendCall.get().name(), "失败必须来自真实后端调用，而非参数解析");
        assertTrue(TenantContext.callWith(TRUSTED_IDENTITY.tenantId(), service::list).isEmpty());
        assertTrue(service.list().isEmpty());
        assertEquals(caller, Scope.capture());
    }

    @Test
    void nativeToolReadsTrustedInvocationIdentityWhenItsWorkerHasNoTenantThreadLocal() {
        var service = new PendingApprovalService();
        var toolkit = toolkit(new AfterSalesTools(new MockAfterSalesBackend(), service, SESSION));
        var identity = new AgentInvocationIdentity("tenant-native", QuotaSubjectType.USER, "U1", true)
            .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, SESSION, "customer-service");
        var context = RuntimeContext.builder().sessionId(SESSION).put(AgentInvocationIdentity.class, identity).build();
        TenantContext.clear();

        var result = toolkit.callTool(call(context)).block(Duration.ofSeconds(5));

        assertTrue(result.getOutput().toString().contains("审批单号 AP-"), () -> "Native tool output: " + result.getOutput());
        var approvals = TenantContext.callWith("tenant-native", service::list);
        assertEquals(1, approvals.size());
        assertEquals(SESSION, approvals.get(0).getSessionId());
        assertTrue(TenantContext.callWith("default", service::list).isEmpty());
        assertNull(TenantContext.get());
    }

    @Test
    void nativeToolWithoutInvocationIdentityDoesNotSubmitToBackend() {
        var backend = mock(AfterSalesBackend.class);
        var toolkit = toolkit(new AfterSalesTools(backend, new PendingApprovalService(), SESSION));
        TenantContext.clear();
        var result = toolkit.callTool(call(RuntimeContext.builder().sessionId(SESSION).build()))
            .block(Duration.ofSeconds(5));
        assertTrue(result.getOutput().toString().contains("tenant context is required"),
            () -> "Native tool output: " + result.getOutput());
        verifyNoInteractions(backend);
        assertNull(TenantContext.get());
    }

    @Test
    void nativeRefundSchemaContainsOnlyTheExistingThreeBusinessArguments() {
        var toolkit = toolkit(new AfterSalesTools(new MockAfterSalesBackend(), new PendingApprovalService(), SESSION));
        Map<?, ?> parameters = toolkit.getTool("submitRefund").getParameters();
        assertEquals(Set.of("orderId", "amount", "reason"), ((Map<?, ?>) parameters.get("properties")).keySet());
    }

    private Toolkit toolkit(AfterSalesTools tools) {
        var toolkit = new Toolkit(ToolkitConfigs.sequential());
        toolkit.registerTool(tools);
        return toolkit;
    }

    private ToolCallParam call(RuntimeContext context) {
        return call(new ToolCase("submitRefund", INPUT,
            "{\"orderId\":\"O1\",\"amount\":\"9.00\",\"reason\":\"测试\"}"), context);
    }

    private ToolCallParam call(ToolCase tool, RuntimeContext context) {
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder().id("native-" + tool.name())
                .name(tool.name()).input(tool.input()).content(tool.json()).build())
            .input(tool.input()).runtimeContext(context).build();
    }

    private RuntimeContext trustedRuntimeContext() {
        return RuntimeContext.builder().userId("state-namespace-not-the-order-owner").sessionId(SESSION)
            .put(AgentInvocationIdentity.class, TRUSTED_IDENTITY).build();
    }

    private AfterSalesBackend recordingBackend(AtomicReference<BackendCall> captured,
                                               Supplier<Mono<String>> reply) {
        return mock(AfterSalesBackend.class, invocation -> {
            if (invocation.getMethod().getDeclaringClass() != AfterSalesBackend.class) {
                return Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            captured.set(new BackendCall(invocation.getMethod().getName(), Scope.capture()));
            return reply.get();
        });
    }

    private static Stream<ToolCase> nativeTools() {
        return Stream.of(
            new ToolCase("checkRefundEligibility", Map.of("orderId", "O1", "withinSevenDays", "true"),
                "{\"orderId\":\"O1\",\"withinSevenDays\":\"true\"}"),
            new ToolCase("submitRefund", INPUT,
                "{\"orderId\":\"O1\",\"amount\":\"9.00\",\"reason\":\"测试\"}"),
            new ToolCase("queryRefundProgress", Map.of("orderId", "O1"), "{\"orderId\":\"O1\"}"),
            new ToolCase("submitReturn", Map.of("orderId", "O1", "reason", "测试"),
                "{\"orderId\":\"O1\",\"reason\":\"测试\"}"),
            new ToolCase("submitExchange", Map.of("orderId", "O1", "reason", "测试", "newSpec", "蓝色"),
                "{\"orderId\":\"O1\",\"reason\":\"测试\",\"newSpec\":\"蓝色\"}"),
            new ToolCase("checkPriceProtection", Map.of("orderId", "O1"), "{\"orderId\":\"O1\"}"),
            new ToolCase("requestInvoice", Map.of("orderId", "O1", "invoiceTitle", "个人"),
                "{\"orderId\":\"O1\",\"invoiceTitle\":\"个人\"}")
        );
    }

    private record ToolCase(String name, Map<String, Object> input, String json) {
        @Override
        public String toString() { return name; }
    }

    private record BackendCall(String name, Scope scope) { }

    private record Scope(String tenantId, AgentInvocationIdentity identity) {
        private static Scope forTenant(String tenant) {
            return new Scope(tenant,
                new AgentInvocationIdentity(tenant, QuotaSubjectType.API_KEY, tenant + "-key", true, 71L)
                    .forInvocation(AgentInvocationIdentity.CHANNEL_API, tenant + "-session", "unrelated-agent"));
        }

        private static Scope capture() {
            return new Scope(TenantContext.get(), AgentInvocationIdentity.capture());
        }

        private void install() {
            TenantContext.set(tenantId);
            AgentInvocationIdentityContext.set(identity);
        }
    }
}
