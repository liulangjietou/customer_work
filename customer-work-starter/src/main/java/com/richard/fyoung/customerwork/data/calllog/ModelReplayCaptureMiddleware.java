package com.richard.fyoung.customerwork.data.calllog;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/** 在最内层冻结最终 ModelCallInput，避免动态参数或 RAG 改写后仍记录旧输入。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ModelReplayCaptureMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ModelReplayCaptureMiddleware.class);

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        AgentReplayCapture capture = AgentReplayCapture.from(context);
        if (capture != null) {
            try {
                capture.recordModelCall(input);
            } catch (Exception e) {
                log.error("agent replay model capture failed, code={}",
                    "CALLLOG-REPLAY-MODEL-CAPTURE-FAIL", e);
            }
        }
        return next.apply(input);
    }
}
