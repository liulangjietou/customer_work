package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 动态生成参数中间件（2.0 Middleware，承接 1.x DynamicGenerateOptionsHook，「按意图自适应推理」）。
 *
 * <p>落在 {@link #onModelCall} 段：当最近一条用户消息命中"投诉 / 退款 / 纠纷"等高风险关键词时，
 * 临时切到"精确档"（更低温度 + 更高推理强度），让模型在敏感场景更稳更严谨；普通闲聊保持默认参数。</p>
 *
 * <p>覆盖采用 {@code GenerateOptions.mergeOptions(override, effective)} 语义（前者非空值优先），
 * 仅覆盖温度与推理强度。默认关闭。异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DynamicOptionsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(DynamicOptionsMiddleware.class);

    private final boolean enabled;
    private final List<String> preciseKeywords;
    private final Double preciseTemperature;
    private final String preciseReasoningEffort;

    public DynamicOptionsMiddleware(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.DynamicOptions cfg = properties.getHooks().getDynamicOptions();
        this.enabled = cfg.isEnabled();
        this.preciseKeywords = cfg.getPreciseKeywords();
        this.preciseTemperature = cfg.getPreciseTemperature();
        this.preciseReasoningEffort = cfg.getPreciseReasoningEffort();
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        try {
            String lastUser = lastUserText(input.messages());
            if (lastUser != null && hitsPreciseKeyword(lastUser)) {
                GenerateOptions merged = mergePrecise(input.options());
                log.info("[OPT] precise-mode keyword hit, temperature={} reasoningEffort={}",
                    preciseTemperature, preciseReasoningEffort);
                return next.apply(new ModelCallInput(
                    input.messages(), input.tools(), merged, input.model()));
            }
        } catch (Exception e) {
            log.error("[OPT] dynamic options failed (use default), code={}", "DYNAMIC_OPT_ERROR", e);
        }
        return next.apply(input);
    }

    /** 把"精确档"覆盖项合并进当前生效参数（override 非空值优先）。 */
    GenerateOptions mergePrecise(GenerateOptions effective) {
        GenerateOptions override = GenerateOptions.builder()
            .temperature(preciseTemperature)
            .reasoningEffort(preciseReasoningEffort)
            .build();
        return GenerateOptions.mergeOptions(override, effective);
    }

    boolean hitsPreciseKeyword(String text) {
        for (String kw : preciseKeywords) {
            if (kw != null && !kw.isBlank() && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String lastUserText(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return null;
    }
}
