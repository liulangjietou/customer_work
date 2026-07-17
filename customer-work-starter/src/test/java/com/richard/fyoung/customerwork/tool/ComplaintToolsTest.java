package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投诉工单工具组单测：建单返回工单号、查单状态、未知工单兜底。
 * @author owlzhangfq@gmail.com
 */
class ComplaintToolsTest {

    private final ComplaintTools tools = new ComplaintTools(new MockComplaintBackend());

    @Test
    void fileComplaint_shouldReturnTicketId() {
        StepVerifier.create(tools.fileComplaint("20260613001", "物流太慢且客服态度差"))
            .assertNext(r -> assertTrue(r.contains("投诉工单") && r.contains("CP")))
            .verifyComplete();
    }

    @Test
    void queryComplaint_shouldHandleUnknownTicket() {
        StepVerifier.create(tools.queryComplaint("CP-not-exist"))
            .assertNext(r -> assertTrue(r.contains("未查询到")))
            .verifyComplete();
    }
}
