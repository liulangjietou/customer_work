package com.example.customerwork.tool;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后 / 退款工具组单测。重点验证两条资金安全红线：
 * 超期拒绝、退款只生成"待人工确认工单"而不直接打款。
 */
class AfterSalesToolsTest {

    private final AfterSalesTools tools = new AfterSalesTools();

    @Test
    void checkRefund_shouldRejectWhenOverSevenDays() {
        StepVerifier.create(tools.checkRefundEligibility("20260613002", "false"))
            .assertNext(result -> assertTrue(result.contains("不满足")))
            .verifyComplete();
    }

    @Test
    void checkRefund_shouldAcceptWithinSevenDays() {
        StepVerifier.create(tools.checkRefundEligibility("20260613001", "true"))
            .assertNext(result -> assertTrue(result.contains("满足")))
            .verifyComplete();
    }

    @Test
    void submitRefund_shouldGenerateHumanReviewTicket_notDirectPayout() {
        StepVerifier.create(tools.submitRefund("20260613001", "299.00", "不想要了"))
            .assertNext(result -> {
                assertTrue(result.contains("退款工单"), "应生成退款工单");
                assertTrue(result.contains("人工"), "涉资金必须转人工复核，不得直接打款");
            })
            .verifyComplete();
    }
}
