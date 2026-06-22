package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 动态生成参数 Hook 单测：命中高风险关键词切精确档，否则沿用默认。
 * @author owlzhangfq@gmail.com
 */
class DynamicGenerateOptionsHookTest {

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    private DynamicGenerateOptionsHook hook(boolean enabled) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getDynamicOptions().setEnabled(enabled);
        props.getHooks().getDynamicOptions().setPreciseTemperature(0.1);
        props.getHooks().getDynamicOptions().setPreciseReasoningEffort("high");
        return new DynamicGenerateOptionsHook(props);
    }

    private PreReasoningEvent event(Agent agent, String userText) {
        Msg user = Msg.builder().role(MsgRole.USER).name("user").textContent(userText).build();
        GenerateOptions base = GenerateOptions.builder().temperature(0.7).build();
        return new PreReasoningEvent(agent, "qwen-max", base, List.of(user));
    }

    @Test
    void shouldSwitchToPreciseProfile_onComplaint() {
        DynamicGenerateOptionsHook hook = hook(true);
        PreReasoningEvent e = event(agent(), "我要投诉，必须退款");

        hook.onEvent(e).block();

        GenerateOptions opts = e.getEffectiveGenerateOptions();
        assertEquals(0.1, opts.getTemperature());
        assertEquals("high", opts.getReasoningEffort());
    }

    @Test
    void shouldKeepDefault_onNormalChat() {
        DynamicGenerateOptionsHook hook = hook(true);
        PreReasoningEvent e = event(agent(), "你们家有什么新品推荐吗");

        hook.onEvent(e).block();

        assertEquals(0.7, e.getEffectiveGenerateOptions().getTemperature());
    }

    @Test
    void shouldPassThrough_whenDisabled() {
        DynamicGenerateOptionsHook hook = hook(false);
        PreReasoningEvent e = event(agent(), "我要投诉退款");
        StepVerifier.create(hook.onEvent(e)).expectNext(e).verifyComplete();
        assertEquals(0.7, e.getEffectiveGenerateOptions().getTemperature());
    }

    @Test
    void priority_shouldBePreprocessing() {
        assertEquals(70, hook(true).priority());
    }
}
