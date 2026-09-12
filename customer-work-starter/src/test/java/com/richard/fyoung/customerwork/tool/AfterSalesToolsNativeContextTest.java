package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 经过真实原生 Toolkit 调度；方法调用前的线程切换与后端回调切换是两处独立边界。 */
class AfterSalesToolsNativeContextTest {
    private static final String SESSION = "session-native-refund";
    private static final Map<String, Object> INPUT = Map.of("orderId", "O1", "amount", "9.00", "reason", "测试");

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

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
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder().id("refund-native")
                .name("submitRefund").input(INPUT)
                .content("{\"orderId\":\"O1\",\"amount\":\"9.00\",\"reason\":\"测试\"}").build())
            .input(INPUT).runtimeContext(context).build();
    }
}
