package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.agent.RuntimeAgentAccessState;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLifecycleMiddlewareTest {

    @Test
    void revokedRuntime_shouldRejectBeforeInvokingDownstreamChain() {
        RuntimeAgentAccessState state = new RuntimeAgentAccessState();
        state.revoke("cs-bot", "revision-2", "a".repeat(64));
        AgentLifecycleMiddleware middleware = new AgentLifecycleMiddleware(state);
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        List<AgentEvent> events = middleware.onAgent(null, null, new AgentInput(List.of()), input -> {
            nextCalled.set(true);
            return Flux.empty();
        }).collectList().block();

        assertFalse(nextCalled.get());
        assertEquals(1, events.size());
        assertEquals(RuntimeAgentAccessState.DISABLED_REPLY,
            ((AgentResultEvent) events.get(0)).getResult().getTextContent());
    }

    @Test
    void newerActiveSnapshot_shouldReopenRuntime() {
        RuntimeAgentAccessState state = new RuntimeAgentAccessState();
        state.revoke("cs-bot", "revision-2", "a".repeat(64));
        state.activate("cs-bot", "revision-3", "b".repeat(64));
        AgentLifecycleMiddleware middleware = new AgentLifecycleMiddleware(state);
        AtomicBoolean nextCalled = new AtomicBoolean(false);

        middleware.onAgent(null, null, new AgentInput(List.of()), input -> {
            nextCalled.set(true);
            return Flux.empty();
        }).blockLast();

        assertTrue(nextCalled.get());
    }
}
