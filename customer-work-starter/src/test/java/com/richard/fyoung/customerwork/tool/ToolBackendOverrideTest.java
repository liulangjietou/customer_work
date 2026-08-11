package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证扩展点：使用者自定义后端可被工具壳直接驱动；ToolRegistrar 用自定义后端时工具组完整注册。
 * @author owlzhangfq@gmail.com
 */
class ToolBackendOverrideTest {

    /** 模拟使用者接入自有订单系统的实现。 */
    static class CustomOrderBackend implements OrderBackend {
        @Override
        public Mono<String> queryOrder(String orderId) {
            return Mono.just("CUSTOM:order=" + orderId);
        }

        @Override
        public Mono<String> queryLogistics(String orderId) {
            return Mono.just("CUSTOM:logistics=" + orderId);
        }
    }

    @Test
    void orderTools_shouldUseCustomBackend() {
        OrderTools tools = new OrderTools(new CustomOrderBackend());
        assertTrue(tools.queryOrder("X1").block().startsWith("CUSTOM:order=X1"));
        assertTrue(tools.queryLogistics("X1").block().startsWith("CUSTOM:logistics=X1"));
    }

    @Test
    void toolRegistrar_shouldRegisterAllBusinessTools_withCustomBackend() {
        Toolkit toolkit = new Toolkit();
        new ToolRegistrar(
            new CustomOrderBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockProductBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockMemberBackend(),
            new com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend(),
            new com.richard.fyoung.customerwork.capability.approval.PendingApprovalService(),
            new com.richard.fyoung.customerwork.capability.handoff.HandoffService(),
            null)
            .registerBusinessTools(toolkit);

        Set<String> names = toolkit.getToolNames();
        assertTrue(names.contains("queryOrder") && names.contains("submitRefund")
            && names.contains("searchKnowledge") && names.contains("transferToHuman")
            && names.contains("recommendProducts") && names.contains("queryRefundProgress")
            && names.contains("cancelOrder") && names.contains("queryPoints")
            && names.contains("fileComplaint"),
            "应注册全部业务工具（售前/订单/售后/会员/投诉/人工）: " + names);
    }
}
