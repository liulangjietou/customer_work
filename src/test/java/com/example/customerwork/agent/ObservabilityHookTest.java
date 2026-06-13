package com.example.customerwork.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 可观测 Hook 单测：必须只读透传事件，且观测逻辑异常绝不打断主链路。
 */
class ObservabilityHookTest {

    private final ObservabilityHook hook = new ObservabilityHook();

    @Test
    void onEvent_shouldPassThroughSameEvent() {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");
        ErrorEvent event = new ErrorEvent(agent, new RuntimeException("boom"));

        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)   // 原样透传，不替换事件实例
            .verifyComplete();
    }

    @Test
    void onEvent_shouldNotThrow_whenEventDataIsIncomplete() {
        // agent.getName() 抛异常，模拟观测时数据不完整；Hook 应吞掉异常并照常透传
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenThrow(new IllegalStateException("no name"));
        ErrorEvent event = new ErrorEvent(agent, new RuntimeException("boom"));

        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)
            .verifyComplete();
    }

    @Test
    void priority_shouldBeLow() {
        org.junit.jupiter.api.Assertions.assertEquals(800, hook.priority());
    }

    @Test
    void onEvent_shouldRecordMetrics_whenMeterRegistryPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityHook metricHook = new ObservabilityHook(registry);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("test-agent");

        // 工具调用事件 -> 计数 + 按 tool 维度打标
        ToolUseBlock toolUse = new ToolUseBlock("c1", "queryOrder", Map.of("orderId", "1"));
        metricHook.onEvent(new PreActingEvent(agent, new Toolkit(), toolUse)).block();
        // 错误事件 -> 错误计数
        metricHook.onEvent(new ErrorEvent(agent, new RuntimeException("x"))).block();

        assertEquals(1.0, registry.counter("customerwork.agent.tool.calls", "tool", "queryOrder").count());
        assertEquals(1.0, registry.counter("customerwork.agent.errors").count());
    }

    @Test
    void onEvent_shouldNotFail_withoutMeterRegistry() {
        // 无 MeterRegistry 时降级为仅日志，不应抛错
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("a");
        StepVerifier.create(new ObservabilityHook().onEvent(new ErrorEvent(agent, new RuntimeException())))
            .expectNextCount(1)
            .verifyComplete();
    }

    @Test
    void onEvent_shouldPassThroughUnhandledEventType() {
        // PreCallEvent 不在 Hook 的特殊处理分支中，应被原样透传
        Agent agent = mock(Agent.class);
        PreCallEvent event = new PreCallEvent(agent, List.of());
        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)
            .verifyComplete();
    }
}
