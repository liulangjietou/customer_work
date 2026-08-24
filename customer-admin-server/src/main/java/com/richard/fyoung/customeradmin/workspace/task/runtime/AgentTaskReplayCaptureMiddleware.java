package com.richard.fyoung.customeradmin.workspace.task.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 捕获异步子智能体的最小可重放输入；无状态，可按 Agent 实例装配。 */
public final class AgentTaskReplayCaptureMiddleware implements MiddlewareBase {

    private static final String AGENT_SPAWN = "agent_spawn";

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (ctx == null || input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        AgentTaskReplayContext replayContext = ctx.get(AgentTaskReplayContext.class);
        List<String> capturedIds = new ArrayList<>();
        for (ToolUseBlock call : input.toolCalls()) {
            if (!AGENT_SPAWN.equals(call.getName()) || !isFireAndForget(call.getInput())) {
                continue;
            }
            String subAgentId = stringValue(call.getInput().get("agent_id"));
            String task = stringValue(call.getInput().get("task"));
            if (!StringUtils.hasText(subAgentId) || !StringUtils.hasText(task)) {
                continue;
            }
            if (replayContext == null) {
                replayContext = new AgentTaskReplayContext();
                ctx.put(AgentTaskReplayContext.class, replayContext);
            }
            replayContext.offer(new AgentTaskReplayContext.ReplaySpec(call.getId(), subAgentId, task));
            capturedIds.add(call.getId());
        }
        if (replayContext == null) {
            return next.apply(input);
        }
        AgentTaskReplayContext capturedContext = replayContext;
        return next.apply(input).doFinally(signal -> capturedContext.discard(capturedIds));
    }

    private boolean isFireAndForget(Map<String, Object> input) {
        Object value = input.get("timeout_seconds");
        if (value instanceof Number number) {
            return number.longValue() == 0L;
        }
        return value != null && "0".equals(value.toString().trim());
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
