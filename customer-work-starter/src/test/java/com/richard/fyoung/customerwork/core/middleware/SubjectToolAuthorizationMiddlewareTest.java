package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.tool.mcp.McpToolAuthorizationRegistry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubjectToolAuthorizationMiddlewareTest {

    private static final String SCOPE = "tenant-a:agent:assistant";
    private static final String MCP_TOOL = "inventory_query";

    private McpToolAuthorizationRegistry registry;
    private SubjectToolAuthorizationMiddleware middleware;

    @BeforeEach
    void setUp() {
        registry = new McpToolAuthorizationRegistry();
        registry.register(SCOPE, List.of(MCP_TOOL), List.of("ADMIN_USER"));
        middleware = new SubjectToolAuthorizationMiddleware(registry);
    }

    @Test
    void shouldAllowConfiguredSubject() {
        AtomicBoolean executed = new AtomicBoolean();
        RuntimeContext context = context(new AgentInvocationIdentity(
            "tenant-a", QuotaSubjectType.ADMIN_USER, "42", true));

        middleware.onActing(null, context, acting(MCP_TOOL), input -> {
            executed.set(true);
            return Flux.empty();
        }).blockLast();

        assertTrue(executed.get());
    }

    @Test
    void shouldFailClosedBeforeToolExecutionWhenSubjectMissing() {
        AtomicBoolean executed = new AtomicBoolean();

        assertThrows(SubjectToolAuthorizationMiddleware.McpToolAuthorizationException.class,
            () -> middleware.onActing(null, context(null), acting(MCP_TOOL), input -> {
                executed.set(true);
                return Flux.empty();
            }).blockLast());

        assertFalse(executed.get());
    }

    @Test
    void shouldDenySubjectNotListedByMcpPolicy() {
        AtomicBoolean executed = new AtomicBoolean();
        RuntimeContext context = context(new AgentInvocationIdentity(
            "tenant-a", QuotaSubjectType.API_KEY, "fingerprint", true));

        assertThrows(SubjectToolAuthorizationMiddleware.McpToolAuthorizationException.class,
            () -> middleware.onActing(null, context, acting(MCP_TOOL), input -> {
                executed.set(true);
                return Flux.empty();
            }).blockLast());

        assertFalse(executed.get());
    }

    @Test
    void shouldNotAffectUnregisteredSystemTool() {
        AtomicBoolean executed = new AtomicBoolean();

        middleware.onActing(null, context(null), acting("query_order"), input -> {
            executed.set(true);
            return Flux.empty();
        }).blockLast();

        assertTrue(executed.get());
    }

    private RuntimeContext context(AgentInvocationIdentity identity) {
        RuntimeContext.Builder builder = RuntimeContext.builder().userId(SCOPE).sessionId("s1");
        if (identity != null) {
            builder.put(AgentInvocationIdentity.class, identity);
        }
        return builder.build();
    }

    private ActingInput acting(String toolName) {
        return new ActingInput(List.of(new ToolUseBlock("tool-1", toolName, Map.of())));
    }
}
