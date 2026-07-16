package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SandboxGuardMiddleware} 单测：破坏性命令拦截、未命中不改写、总开关关闭时直接放行。
 * @author owlzhangfq@gmail.com
 */
class SandboxGuardMiddlewareTest {

    private AdminSandboxProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AdminSandboxProperties();
    }

    private ToolUseBlock toolUse(String toolName, Map<String, Object> input) {
        return new ToolUseBlock("call-1", toolName, input, Map.of());
    }

    @Test
    void guard_shouldRewriteDestructiveParam_whenPatternMatched() {
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "rm -rf sessions/abc");

        ToolUseBlock rewritten = middleware.guard(toolUse("shell_execute", input));

        assertEquals(AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER, rewritten.getInput().get("command"));
        assertEquals(1, middleware.destructiveHitCount());
    }

    @Test
    void guard_shouldReturnNull_whenNoDestructivePatternMatched() {
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "mvn test");

        ToolUseBlock rewritten = middleware.guard(toolUse("shell_execute", input));

        assertNull(rewritten, "未命中危险模式不应改写");
        assertEquals(0, middleware.destructiveHitCount());
    }

    @Test
    void guard_shouldMatch_gitDirectoryTampering() {
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("path", "sessions/abc/.git/config");

        ToolUseBlock rewritten = middleware.guard(toolUse("write_file", input));

        assertEquals(AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER, rewritten.getInput().get("path"));
    }

    @Test
    void guard_shouldOnlyRewriteMatchedParam_keepingOthersIntact() {
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "rm -rf /");
        input.put("description", "clean up build output");

        ToolUseBlock rewritten = middleware.guard(toolUse("shell_execute", input));

        assertEquals(AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER, rewritten.getInput().get("command"));
        assertEquals("clean up build output", rewritten.getInput().get("description"));
    }

    @Test
    void onActing_shouldPassThroughUnchanged_whenGuardDisabled() {
        properties.getGuard().setEnabled(false);
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "rm -rf /");
        ActingInput actingInput = new ActingInput(List.of(toolUse("shell_execute", input)));

        AtomicReference<ActingInput> received = new AtomicReference<>();
        Flux<AgentEvent> result = middleware.onActing(null, null, actingInput, in -> {
            received.set(in);
            return Flux.empty();
        });
        result.blockLast();

        assertEquals(actingInput, received.get(), "总开关关闭时应原样透传，不做任何改写");
        assertEquals(0, middleware.destructiveHitCount());
    }

    @Test
    void onActing_shouldRewriteToolCalls_whenDestructivePatternMatched() {
        SandboxGuardMiddleware middleware = new SandboxGuardMiddleware(properties, new SandboxRiskDetector(properties));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "rm -rf /");
        ActingInput actingInput = new ActingInput(List.of(toolUse("shell_execute", input)));

        AtomicReference<ActingInput> received = new AtomicReference<>();
        Flux<AgentEvent> result = middleware.onActing(null, null, actingInput, in -> {
            received.set(in);
            return Flux.empty();
        });
        result.blockLast();

        assertTrue(received.get() != actingInput, "命中危险模式应传入改写后的新 ActingInput");
        assertEquals(AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER,
            received.get().toolCalls().get(0).getInput().get("command"));
    }
}
