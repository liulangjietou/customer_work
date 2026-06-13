package com.example.customerwork.tool;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工转接工具单测：生成工单号并回带转接原因。
 * @author owlzhangfq@gmail.com
 */
class HumanHandoffToolsTest {

    private final HumanHandoffTools tools = new HumanHandoffTools();

    @Test
    void transferToHuman_shouldReturnWorkOrder_withReason() {
        StepVerifier.create(tools.transferToHuman("用户投诉升级"))
            .assertNext(result -> {
                assertTrue(result.contains("人工坐席"), "应提示已转人工");
                assertTrue(result.contains("用户投诉升级"), "应回带转接原因");
                assertTrue(result.contains("HO"), "应包含工单号");
            })
            .verifyComplete();
    }
}
