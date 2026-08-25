package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware;
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HarnessMemoryPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void apply_shouldDisableDefaultMemoryHooksAndTools_whenMemoryIsNotEnabled() {
        try (HarnessAgent agent = buildAgent(false)) {
            List<MiddlewareBase> middlewares = agent.getDelegate().getMiddlewares();
            Set<String> toolNames = agent.getToolkit().getToolNames();

            assertFalse(middlewares.stream().anyMatch(MemoryFlushMiddleware.class::isInstance));
            assertFalse(middlewares.stream().anyMatch(MemoryMaintenanceMiddleware.class::isInstance));
            assertFalse(toolNames.stream().anyMatch(name -> name.startsWith("memory_")));
        }
    }

    @Test
    void apply_shouldKeepMemoryHooksAndTools_whenMemoryIsEnabled() {
        try (HarnessAgent agent = buildAgent(true)) {
            List<MiddlewareBase> middlewares = agent.getDelegate().getMiddlewares();
            Set<String> toolNames = agent.getToolkit().getToolNames();

            assertTrue(middlewares.stream().anyMatch(MemoryFlushMiddleware.class::isInstance));
            assertTrue(middlewares.stream().anyMatch(MemoryMaintenanceMiddleware.class::isInstance));
            assertTrue(toolNames.stream().anyMatch(name -> name.startsWith("memory_")));
        }
    }

    private HarnessAgent buildAgent(boolean memoryEnabled) {
        Model model = mock(Model.class);
        ReActAgent inner = ReActAgent.builder()
            .name("memory-policy-test")
            .model(model)
            .toolkit(new Toolkit())
            .stateStore(new InMemoryAgentStateStore())
            .build();
        HarnessAgent.Builder builder = HarnessAgent.Builder.fromAgent(inner)
            .workspace(workspace);
        HarnessMemoryPolicy.apply(builder, memoryEnabled, model);
        return builder.build();
    }
}
