package com.richard.fyoung.customerwork.agent;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 工具护栏 Hook 单测：执行前注入公共参数、对数值参数做上限钳制。
 * @author owlzhangfq@gmail.com
 */
class ToolGuardHookTest {

    private Agent agent() {
        Agent agent = mock(Agent.class);
        lenient().when(agent.getName()).thenReturn("CustomerServiceAgent-u1");
        return agent;
    }

    private ToolGuardHook hook(java.util.function.Consumer<CustomerWorkProperties.Hooks.ToolGuard> cfg) {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getToolGuard().setEnabled(true);
        cfg.accept(props.getHooks().getToolGuard());
        return new ToolGuardHook(props);
    }

    @Test
    void shouldInjectMissingCommonParam() {
        ToolGuardHook hook = hook(c -> c.getInjectParams().put("channel", "online"));
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund", Map.of("amount", 100));
        PreActingEvent event = new PreActingEvent(agent(), new Toolkit(), use);

        hook.onEvent(event).block();

        assertEquals("online", event.getToolUse().getInput().get("channel"));
    }

    @Test
    void shouldNotOverrideExistingParam() {
        ToolGuardHook hook = hook(c -> c.getInjectParams().put("channel", "online"));
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund",
            Map.of("amount", 100, "channel", "vip"));
        PreActingEvent event = new PreActingEvent(agent(), new Toolkit(), use);

        hook.onEvent(event).block();

        assertEquals("vip", event.getToolUse().getInput().get("channel"));
    }

    @Test
    void shouldClampNumericParamOverCap() {
        ToolGuardHook hook = hook(c -> c.getNumericCaps().put("amount", 1000.0));
        Map<String, Object> input = new HashMap<>();
        input.put("amount", 5000);
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund", input);
        PreActingEvent event = new PreActingEvent(agent(), new Toolkit(), use);

        hook.onEvent(event).block();

        assertEquals(1000.0, event.getToolUse().getInput().get("amount"));
    }

    @Test
    void shouldKeepValueUnderCap() {
        ToolGuardHook hook = hook(c -> c.getNumericCaps().put("amount", 1000.0));
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund", Map.of("amount", 800));
        PreActingEvent event = new PreActingEvent(agent(), new Toolkit(), use);

        hook.onEvent(event).block();

        assertEquals(800, event.getToolUse().getInput().get("amount"));
    }

    @Test
    void shouldPassThrough_whenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();   // 默认关闭
        ToolGuardHook hook = new ToolGuardHook(props);
        ToolUseBlock use = new ToolUseBlock("c1", "submitRefund", Map.of("amount", 5000));
        PreActingEvent event = new PreActingEvent(agent(), new Toolkit(), use);
        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
        assertEquals(5000, event.getToolUse().getInput().get("amount"));
    }

    @Test
    void priority_shouldBeSystemLevel() {
        assertEquals(20, hook(c -> {}).priority());
    }
}
