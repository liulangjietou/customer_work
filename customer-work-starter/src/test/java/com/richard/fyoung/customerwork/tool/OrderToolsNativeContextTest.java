package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentityContext;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** 原生工具必须消费服务端完整快照，不使用模型参数或残留线程身份。 */
class OrderToolsNativeContextTest {
    private static final AgentInvocationIdentity TRUSTED = new AgentInvocationIdentity(
        "tenant-native-order", QuotaSubjectType.USER, "order-owner", true, 23L)
        .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, "native-order-session", "customer-service");
    private static final AgentInvocationIdentity STALE = new AgentInvocationIdentity(
        "tenant-stale", QuotaSubjectType.USER, "stale-user", true);

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AgentInvocationIdentityContext.clear();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void trustedRuntimeIdentityReachesBackendAndCallerIsRestored(ToolCase tool) {
        var captured = new AtomicReference<Scope>();
        Toolkit toolkit = toolkit(recordingBackend(captured));
        installStale();
        RuntimeContext context = RuntimeContext.builder().userId("untrusted-model-user")
            .put(AgentInvocationIdentity.class, TRUSTED).build();
        invoke(toolkit, tool, context);
        assertEquals(new Scope(TRUSTED.tenantId(), TRUSTED), captured.get());
        assertEquals(new Scope(STALE.tenantId(), STALE), Scope.capture());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void missingRuntimeSnapshotDoesNotInheritThreadOrUserId(ToolCase tool) {
        var captured = new AtomicReference<Scope>();
        Toolkit toolkit = toolkit(recordingBackend(captured));
        installStale();
        invoke(toolkit, tool, RuntimeContext.builder().userId(TRUSTED.subjectId()).build());
        assertEquals(new Scope(null, null), captured.get());
        assertEquals(new Scope(STALE.tenantId(), STALE), Scope.capture());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void modelSchemaContainsOnlyOriginalBusinessArguments(ToolCase tool) {
        Toolkit toolkit = toolkit(new MockOrderBackend());
        Map<?, ?> schema = toolkit.getTool(tool.name()).getParameters();
        assertEquals(tool.input().keySet(), ((Map<?, ?>) schema.get("properties")).keySet());
    }

    private static OrderBackend recordingBackend(AtomicReference<Scope> captured) {
        return mock(OrderBackend.class, invocation -> {
            if (invocation.getMethod().getReturnType() == Mono.class) {
                captured.set(Scope.capture());
                return Mono.just("recorded-backend-result");
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    private static Toolkit toolkit(OrderBackend backend) {
        Toolkit toolkit = new Toolkit(ToolkitConfigs.sequential());
        toolkit.registerTool(new OrderTools(backend));
        return toolkit;
    }

    private static void invoke(Toolkit toolkit, ToolCase tool, RuntimeContext context) {
        var result = toolkit.callTool(ToolCallParam.builder()
            .toolUseBlock(ToolUseBlock.builder().id("native-order-scope").name(tool.name())
                .input(tool.input()).content(tool.content()).build())
            .input(tool.input()).runtimeContext(context).build()).block(Duration.ofSeconds(5));
        assertNotNull(result);
        String output = result.getOutput().toString();
        assertFalse(output.contains("Parameter validation failed"), output);
        assertTrue(output.contains("recorded-backend-result"), output);
    }

    private static void installStale() {
        TenantContext.set(STALE.tenantId());
        AgentInvocationIdentityContext.set(STALE);
    }

    private static Stream<ToolCase> cases() {
        return Stream.of(
            new ToolCase("queryOrder", Map.of("orderId", "O1"), "{\"orderId\":\"O1\"}"),
            new ToolCase("queryLogistics", Map.of("orderId", "O1"), "{\"orderId\":\"O1\"}"),
            new ToolCase("urgeShipment", Map.of("orderId", "O1"), "{\"orderId\":\"O1\"}"),
            new ToolCase("modifyAddress", Map.of("orderId", "O1", "newAddress", "测试地址"),
                "{\"orderId\":\"O1\",\"newAddress\":\"测试地址\"}"),
            new ToolCase("cancelOrder", Map.of("orderId", "O1", "reason", "测试取消"),
                "{\"orderId\":\"O1\",\"reason\":\"测试取消\"}")
        );
    }

    private record ToolCase(String name, Map<String, Object> input, String content) {}
    private record Scope(String tenant, AgentInvocationIdentity identity) {
        static Scope capture() { return new Scope(TenantContext.get(), AgentInvocationIdentity.capture()); }
    }
}
