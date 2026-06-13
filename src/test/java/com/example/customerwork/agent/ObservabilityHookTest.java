package com.example.customerwork.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

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
    void onEvent_shouldPassThroughUnhandledEventType() {
        // PreCallEvent 不在 Hook 的特殊处理分支中，应被原样透传
        Agent agent = mock(Agent.class);
        PreCallEvent event = new PreCallEvent(agent, List.of());
        StepVerifier.create(hook.onEvent(event))
            .expectNext(event)
            .verifyComplete();
    }
}
