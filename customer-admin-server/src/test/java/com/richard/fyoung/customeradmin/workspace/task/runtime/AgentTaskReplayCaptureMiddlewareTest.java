package com.richard.fyoung.customeradmin.workspace.task.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentTaskReplayCaptureMiddlewareTest {

    @Test
    void onActing_shouldExposeOnlyFireAndForgetInputDuringToolExecution() {
        AgentTaskReplayCaptureMiddleware middleware = new AgentTaskReplayCaptureMiddleware();
        RuntimeContext context = RuntimeContext.builder().userId("tenant-a::parent").sessionId("s1").build();
        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("call-1", "agent_spawn",
                Map.of("agent_id", "reviewer", "task", "review this change", "timeout_seconds", 0)),
            new ToolUseBlock("call-2", "agent_spawn",
                Map.of("agent_id", "writer", "task", "write docs", "timeout_seconds", "0")),
            new ToolUseBlock("call-3", "agent_spawn",
                Map.of("agent_id", "sync-agent", "task", "run now", "timeout_seconds", 30))));
        AtomicReference<AgentTaskReplayContext.ReplaySpec> claimed = new AtomicReference<>();

        middleware.onActing(null, context, input, ignored -> {
            AgentTaskReplayContext replayContext = context.get(AgentTaskReplayContext.class);
            assertNotNull(replayContext);
            claimed.set(replayContext.claim("reviewer"));
            assertNull(replayContext.claim("sync-agent"), "同步子任务不能进入宕机重放队列");
            return Flux.<AgentEvent>empty();
        }).blockLast();

        assertNotNull(claimed.get());
        assertEquals("review this change", claimed.get().input());
        assertNull(context.get(AgentTaskReplayContext.class).claim("writer"),
            "本轮未被仓储消费的捕获项必须在 Acting 结束时清理");
    }

    @Test
    void onActing_shouldIgnoreMalformedSpawnCalls() {
        AgentTaskReplayCaptureMiddleware middleware = new AgentTaskReplayCaptureMiddleware();
        RuntimeContext context = RuntimeContext.builder().sessionId("s1").build();
        ActingInput input = new ActingInput(List.of(
            new ToolUseBlock("call-1", "agent_spawn", Map.of("task", "missing agent", "timeout_seconds", 0)),
            new ToolUseBlock("call-2", "query_order", Map.of("timeout_seconds", 0))));

        middleware.onActing(null, context, input, ignored -> Flux.<AgentEvent>empty()).blockLast();

        assertNull(context.get(AgentTaskReplayContext.class), "没有合法异步提交时不应创建重放上下文");
    }
}
