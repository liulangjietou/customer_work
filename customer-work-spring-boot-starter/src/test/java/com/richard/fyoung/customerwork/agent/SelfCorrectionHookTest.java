package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 自我纠错 Hook 单测：承诺打款却未调退款工具时强制重新推理，并受次数上限约束。
 * @author owlzhangfq@gmail.com
 */
class SelfCorrectionHookTest {

    private SelfCorrectionHook hook(boolean enabled, int maxCorrections) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getSelfCorrection().setEnabled(enabled);
        props.getHooks().getSelfCorrection().setMaxCorrections(maxCorrections);
        return new SelfCorrectionHook(props);
    }

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    private PostReasoningEvent reasoning(Agent agent, Msg msg) {
        return new PostReasoningEvent(agent, "qwen-max", null, msg);
    }

    @Test
    void shouldForceReReasoning_whenPromisingPaymentWithoutRefundTool() {
        SelfCorrectionHook hook = hook(true, 1);
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .textContent("已为您退款，款项稍后到账").build();
        PostReasoningEvent event = reasoning(agent(), msg);

        hook.onEvent(event).block();

        assertTrue(event.isGotoReasoningRequested(), "应触发强制重新推理");
    }

    @Test
    void shouldNotCorrect_whenRefundToolCalled() {
        SelfCorrectionHook hook = hook(true, 1);
        ToolUseBlock refund = new ToolUseBlock("c1", "submitRefund", Map.of("orderId", "1"));
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .content(List.of(TextBlock.builder().text("已为您退款").build(), refund)).build();
        PostReasoningEvent event = reasoning(agent(), msg);

        hook.onEvent(event).block();

        assertFalse(event.isGotoReasoningRequested(), "调用了退款工具不应纠错");
    }

    @Test
    void shouldNotCorrect_whenNoPaymentPromise() {
        SelfCorrectionHook hook = hook(true, 1);
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .textContent("您的订单已发货").build();
        PostReasoningEvent event = reasoning(agent(), msg);
        hook.onEvent(event).block();
        assertFalse(event.isGotoReasoningRequested());
    }

    @Test
    void shouldRespectMaxCorrections() {
        SelfCorrectionHook hook = hook(true, 1);
        Agent agent = agent();
        Msg msg1 = Msg.builder().role(MsgRole.ASSISTANT).name("bot").textContent("已退款").build();
        PostReasoningEvent first = reasoning(agent, msg1);
        hook.onEvent(first).block();
        assertTrue(first.isGotoReasoningRequested());

        // 同一会话第二次：已达上限，不再纠错
        Msg msg2 = Msg.builder().role(MsgRole.ASSISTANT).name("bot").textContent("已打款").build();
        PostReasoningEvent second = reasoning(agent, msg2);
        hook.onEvent(second).block();
        assertFalse(second.isGotoReasoningRequested(), "应受 maxCorrections 上限约束");
    }

    @Test
    void shouldPassThrough_whenDisabled() {
        SelfCorrectionHook hook = hook(false, 1);
        Msg msg = Msg.builder().role(MsgRole.ASSISTANT).name("bot").textContent("已退款").build();
        PostReasoningEvent event = reasoning(agent(), msg);
        hook.onEvent(event).block();
        assertFalse(event.isGotoReasoningRequested());
    }

    @Test
    void priority_shouldRunBeforeObservability() {
        assertEquals(200, hook(true, 1).priority());
    }
}
