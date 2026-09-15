package com.richard.fyoung.customeradmin.aiconfig.agent.trial;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** 独立于提示词与可选护栏，所有执行路径在进入工具前必须命中本次固定允许清单。 */
final class AgentDraftTrialToolGuard implements MiddlewareBase {
    private final Set<String> allowed;
    private final List<String> attempted = new CopyOnWriteArrayList<>();

    AgentDraftTrialToolGuard(Set<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                    Function<ActingInput, Flux<AgentEvent>> next) {
        for (var call : input.toolCalls()) {
            attempted.add(call.getName());
            if (!allowed.contains(call.getName())) return Flux.error(new RestrictedToolException());
        }
        return next.apply(input);
    }

    List<String> attemptedTools() {
        return List.copyOf(attempted);
    }

    static final class RestrictedToolException extends RuntimeException {
        RestrictedToolException() {
            super("Trial tool is outside the fixed read-only scope");
        }
    }
}
