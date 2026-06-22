package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.security.SensitiveDataMasker;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 出站脱敏 Hook 单测：开启时改写最终回复，关闭/无命中时原样透传。
 * @author owlzhangfq@gmail.com
 */
class MaskingHookTest {

    private MaskingHook hook(boolean enabled) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(enabled);
        return new MaskingHook(props, new SensitiveDataMasker(props));
    }

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    @Test
    void shouldMaskFinalReply_whenEnabled() {
        MaskingHook hook = hook(true);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .textContent("已为您登记，联系电话 13800138000").build();
        PostCallEvent event = new PostCallEvent(agent(), reply);

        hook.onEvent(event).block();

        String masked = event.getFinalMessage().getTextContent();
        assertTrue(masked.contains("***"), "应已脱敏: " + masked);
        assertEquals("已为您登记，联系电话 ***", masked);
    }

    @Test
    void shouldPassThrough_whenDisabled() {
        MaskingHook hook = hook(false);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .textContent("电话 13800138000").build();
        PostCallEvent event = new PostCallEvent(agent(), reply);

        hook.onEvent(event).block();

        assertEquals("电话 13800138000", event.getFinalMessage().getTextContent());
    }

    @Test
    void shouldNotTouchNonPostCallEvents() {
        MaskingHook hook = hook(true);
        PreCallEvent event = new PreCallEvent(agent(), List.of());
        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
    }

    @Test
    void shouldNotFail_whenFinalMessageHasNoSensitiveData() {
        MaskingHook hook = hook(true);
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).name("bot")
            .textContent("订单已发货").build();
        PostCallEvent event = new PostCallEvent(agent(), reply);
        hook.onEvent(event).block();
        assertEquals("订单已发货", event.getFinalMessage().getTextContent());
    }

    @Test
    void priority_shouldRunLate() {
        assertEquals(900, hook(true).priority());
    }

    @Test
    void shouldMaskToolResult_whenEnabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        props.getHooks().getMasking().setMaskToolResults(true);
        MaskingHook hook = new MaskingHook(props, new SensitiveDataMasker(props));

        ToolUseBlock use = new ToolUseBlock("c1", "queryOrder", Map.of("orderId", "1"));
        ToolResultBlock result = new ToolResultBlock("c1", "queryOrder",
            TextBlock.builder().text("收件人电话 13800138000").build());
        PostActingEvent event = new PostActingEvent(agent(), new Toolkit(), use, result);

        hook.onEvent(event).block();

        String masked = ((TextBlock) event.getToolResult().getOutput().get(0)).getText();
        assertTrue(masked.contains("***"), "工具结果应脱敏: " + masked);
    }

    @Test
    void shouldNotMaskToolResult_whenFlagOff() {
        MaskingHook hook = hook(true);   // maskToolResults 默认 false
        ToolUseBlock use = new ToolUseBlock("c1", "queryOrder", Map.of("orderId", "1"));
        ToolResultBlock result = new ToolResultBlock("c1", "queryOrder",
            TextBlock.builder().text("电话 13800138000").build());
        PostActingEvent event = new PostActingEvent(agent(), new Toolkit(), use, result);

        hook.onEvent(event).block();

        String text = ((TextBlock) event.getToolResult().getOutput().get(0)).getText();
        assertEquals("电话 13800138000", text);
    }
}
