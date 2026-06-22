package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 延迟埋点 Hook 单测：配对事件记录耗时 Timer，关闭/无注册表时安全降级。
 * @author owlzhangfq@gmail.com
 */
class LatencyHookTest {

    @SuppressWarnings("unchecked")
    private LatencyHook hook(boolean enabled, MeterRegistry registry) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getLatency().setEnabled(enabled);
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(registry);
        return new LatencyHook(props, provider);
    }

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    @Test
    void shouldRecordEndToEndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyHook hook = hook(true, registry);
        Agent agent = agent();

        hook.onEvent(new PreCallEvent(agent, List.of())).block();
        Msg reply = Msg.builder().role(MsgRole.ASSISTANT).name("bot").textContent("好的").build();
        hook.onEvent(new PostCallEvent(agent, reply)).block();

        assertEquals(1, registry.get("customerwork.agent.latency.e2e").timer().count());
    }

    @Test
    void shouldRecordToolTimerWithTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyHook hook = hook(true, registry);
        Agent agent = agent();
        ToolUseBlock use = new ToolUseBlock("call-1", "queryOrder", Map.of("orderId", "1"));
        ToolResultBlock result = new ToolResultBlock("call-1", "queryOrder",
            TextBlock.builder().text("ok").build());

        hook.onEvent(new PreActingEvent(agent, new Toolkit(), use)).block();
        hook.onEvent(new PostActingEvent(agent, new Toolkit(), use, result)).block();

        assertEquals(1, registry.get("customerwork.agent.latency.tool").tag("tool", "queryOrder")
            .timer().count());
    }

    @Test
    void shouldNotRecord_whenDisabled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LatencyHook hook = hook(false, registry);
        Agent agent = agent();
        hook.onEvent(new PreCallEvent(agent, List.of())).block();
        hook.onEvent(new PostCallEvent(agent,
            Msg.builder().role(MsgRole.ASSISTANT).name("b").textContent("x").build())).block();
        assertNull(registry.find("customerwork.agent.latency.e2e").timer());
    }

    @Test
    void shouldPassThroughAndNotFail_withoutRegistry() {
        LatencyHook hook = hook(true, null);
        PreCallEvent event = new PreCallEvent(agent(), List.of());
        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
    }

    @Test
    void priority_shouldRunEarly() {
        assertEquals(50, hook(true, null).priority());
    }

    @Test
    void registryProvider_isUsed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        assertNotNull(hook(true, registry));
    }
}
