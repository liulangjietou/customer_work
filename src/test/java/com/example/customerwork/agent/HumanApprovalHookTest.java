package com.example.customerwork.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 人工确认 Hook 单测（进阶「Human-in-the-Loop」）：
 * 受控的高风险工具执行后应请求暂停 Agent，普通工具不受影响。
 * @author owlzhangfq@gmail.com
 */
class HumanApprovalHookTest {

    private final HumanApprovalHook hook = new HumanApprovalHook(Set.of("submitRefund"));
    private final Agent agent = mock(Agent.class);
    private final Toolkit toolkit = new Toolkit();

    private PostActingEvent postActing(String toolName) {
        ToolUseBlock toolUse = new ToolUseBlock("call-1", toolName, Map.of("amount", "299"));
        ToolResultBlock result = ToolResultBlock.text("已生成退款工单");
        return new PostActingEvent(agent, toolkit, toolUse, result);
    }

    @Test
    void shouldRequestStop_forGuardedTool() {
        PostActingEvent event = postActing("submitRefund");

        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)
            .verifyComplete();

        assertTrue(event.isStopRequested(), "受控工具执行后应请求暂停 Agent 等待人工确认");
    }

    @Test
    void shouldNotRequestStop_forNormalTool() {
        PostActingEvent event = postActing("queryOrder");

        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)
            .verifyComplete();

        assertFalse(event.isStopRequested(), "普通工具不应触发人工确认暂停");
    }

    @Test
    void priority_shouldBeHigherThanObservability() {
        assertTrue(hook.priority() < new ObservabilityHook().priority(),
            "人工确认闸门应优先于观测 Hook");
    }
}
