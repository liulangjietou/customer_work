package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.agent.RuntimeAgentAccessState;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/** 智能体生命周期总闸门：撤销快照生效后，在模型推理和工具调用之前终止整次调用。 */
public class AgentLifecycleMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleMiddleware.class);
    private static final String ERROR_CODE = "AGENT-RUNTIME-REVOKED";

    private final RuntimeAgentAccessState accessState;

    public AgentLifecycleMiddleware(RuntimeAgentAccessState accessState) {
        this.accessState = accessState;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        RuntimeAgentAccessState.Snapshot snapshot = accessState.snapshot();
        if (snapshot.active()) {
            return next.apply(input);
        }
        log.error("agent invocation rejected by runtime lifecycle, code={}, targetCode={}, revision={}",
            ERROR_CODE, snapshot.targetCode(), snapshot.revision());
        Msg reply = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .name(agent == null ? "assistant" : agent.getName())
            .content(TextBlock.builder().text(RuntimeAgentAccessState.DISABLED_REPLY).build())
            .build();
        return Flux.just(new AgentResultEvent(reply));
    }
}
