package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单工具组单测：工具壳委托默认 Mock 后端，验证响应式逻辑（无需 API Key）。
 * @author owlzhangfq@gmail.com
 */
class OrderToolsTest {

    private final OrderTools tools = new OrderTools(new MockOrderBackend());

    @Test
    void queryOrder_shouldReturnStatus_forKnownOrder() {
        StepVerifier.create(tools.queryOrder("20260613001"))
            .assertNext(result -> {
                assertTrue(result.contains("已发货"), "应包含订单状态");
                assertTrue(result.contains("299.00"), "应包含金额");
            })
            .verifyComplete();
    }

    @Test
    void queryOrder_shouldHandleUnknownOrder() {
        StepVerifier.create(tools.queryOrder("不存在的订单"))
            .assertNext(result -> assertTrue(result.contains("未查询到订单")))
            .verifyComplete();
    }

    @Test
    void queryLogistics_shouldReturnTrace_forKnownOrder() {
        StepVerifier.create(tools.queryLogistics("20260613001"))
            .assertNext(result -> assertTrue(result.contains("派送中")))
            .verifyComplete();
    }

    @Test
    void queryLogistics_shouldHandleUnknownOrder() {
        StepVerifier.create(tools.queryLogistics("9999"))
            .assertNext(result -> assertTrue(result.contains("未查询到")))
            .verifyComplete();
    }
}
