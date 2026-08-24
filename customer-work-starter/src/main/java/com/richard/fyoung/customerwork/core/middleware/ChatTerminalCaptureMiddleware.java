package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCaptureContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 从所有受治理的 Agent 路径采集终止原因与真实 token 用量。
 *
 * <p>没有终止采集上下文时完全透传，因此不会改变工作台、调度任务等既有调用方行为。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Component
public class ChatTerminalCaptureMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(contextView -> {
            ChatTerminalCapture capture = ChatTerminalCaptureContext.get(contextView);
            if (capture == null) {
                return next.apply(input);
            }
            return next.apply(input)
                .doOnNext(capture::accept)
                .doOnError(error -> capture.markError());
        });
    }
}
