package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.handoff.HandoffService;
import com.richard.fyoung.customerwork.handoff.HandoffStatus;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工转接工具单测：未注入 HandoffService 时退化为纯文案兜底；
 * 注入后应真实登记可查询、可流转的 {@link com.richard.fyoung.customerwork.handoff.HandoffTicket}。
 * @author owlzhangfq@gmail.com
 */
class HumanHandoffToolsTest {

    @Test
    void transferToHuman_withoutHandoffService_shouldReturnFallbackWorkOrder() {
        HumanHandoffTools tools = new HumanHandoffTools();
        StepVerifier.create(tools.transferToHuman("用户投诉升级"))
            .assertNext(result -> {
                assertTrue(result.contains("人工坐席"), "应提示已转人工");
                assertTrue(result.contains("用户投诉升级"), "应回带转接原因");
                assertTrue(result.contains("HO"), "应包含工单号");
            })
            .verifyComplete();
    }

    @Test
    void transferToHuman_withHandoffService_shouldCreateRealTicket() {
        HandoffService handoffService = new HandoffService();
        HumanHandoffTools tools = new HumanHandoffTools(handoffService);

        StepVerifier.create(tools.transferToHuman("涉及大额退款"))
            .assertNext(result -> {
                assertTrue(result.contains("HO-"), "应包含真实工单号前缀");
                assertTrue(result.contains("涉及大额退款"), "应回带转接原因");
            })
            .verifyComplete();

        // 工单应真实落库、状态为 PENDING（等待坐席接单）
        assertEquals(1, handoffService.list().size());
        assertEquals(HandoffStatus.PENDING, handoffService.list().get(0).getStatus());
        assertEquals("涉及大额退款", handoffService.list().get(0).getReason());
    }
}
