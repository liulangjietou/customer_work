package com.example.customerwork;

import com.example.customerwork.tool.AfterSalesTools;
import com.example.customerwork.tool.OrderTools;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * 工具层单元测试。
 *
 * <p>这些测试只验证工具自身的响应式逻辑（不调用真实模型，无需 API Key），
 * 用 {@link StepVerifier} 校验 Mono 链路——这是 AgentScope 推荐的响应式测试方式。</p>
 */
class CustomerServiceToolsTest {

    @Test
    void queryOrder_shouldReturnStatus_forKnownOrder() {
        OrderTools tools = new OrderTools();
        StepVerifier.create(tools.queryOrder("20260613001"))
            .assertNext(result -> {
                org.junit.jupiter.api.Assertions.assertNotNull(result);
                org.junit.jupiter.api.Assertions.assertTrue(result.contains("已发货"));
            })
            .verifyComplete();
    }

    @Test
    void queryOrder_shouldHandleUnknownOrder() {
        OrderTools tools = new OrderTools();
        StepVerifier.create(tools.queryOrder("不存在的订单"))
            .assertNext(result ->
                org.junit.jupiter.api.Assertions.assertTrue(result.contains("未查询到订单")))
            .verifyComplete();
    }

    @Test
    void checkRefund_shouldRejectWhenOverSevenDays() {
        AfterSalesTools tools = new AfterSalesTools();
        StepVerifier.create(tools.checkRefundEligibility("20260613002", "false"))
            .assertNext(result ->
                org.junit.jupiter.api.Assertions.assertTrue(result.contains("不满足")))
            .verifyComplete();
    }

    @Test
    void submitRefund_shouldGenerateHumanReviewTicket() {
        AfterSalesTools tools = new AfterSalesTools();
        StepVerifier.create(tools.submitRefund("20260613001", "299.00", "不想要了"))
            .assertNext(result -> {
                // 验证：退款只生成待人工确认工单，不直接打款
                org.junit.jupiter.api.Assertions.assertTrue(result.contains("退款工单"));
                org.junit.jupiter.api.Assertions.assertTrue(result.contains("人工"));
            })
            .verifyComplete();
    }
}
