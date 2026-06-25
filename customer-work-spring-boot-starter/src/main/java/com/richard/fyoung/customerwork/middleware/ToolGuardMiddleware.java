package com.richard.fyoung.customerwork.middleware;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具调用护栏中间件（2.0 Middleware，承接 1.x ToolGuardHook，对应「安全 · 工具入参治理」）。
 *
 * <p>落在 {@link #onActing} 段：在工具真正执行前改写 {@link ActingInput} 中的工具入参——</p>
 * <ul>
 *   <li><b>公共参数注入</b>：把渠道 / 租户 / 调用来源等公共上下文注入到每个工具调用（仅当缺失该键时）；</li>
 *   <li><b>数值上限钳制</b>：对指定数值参数（如退款金额 amount）做上限保护，超限改写为上限值并告警。</li>
 * </ul>
 *
 * <p>默认关闭。异常不打断主链路（原样放行）。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class ToolGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardMiddleware.class);

    private final boolean enabled;
    private final Map<String, String> injectParams;
    private final Map<String, Double> numericCaps;

    public ToolGuardMiddleware(CustomerWorkProperties properties) {
        CustomerWorkProperties.Hooks.ToolGuard cfg = properties.getHooks().getToolGuard();
        this.enabled = cfg.isEnabled();
        this.injectParams = cfg.getInjectParams();
        this.numericCaps = cfg.getNumericCaps();
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        try {
            List<ToolUseBlock> rewritten = new ArrayList<>(input.toolCalls().size());
            boolean anyChanged = false;
            for (ToolUseBlock use : input.toolCalls()) {
                ToolUseBlock guarded = guard(use);
                if (guarded != null) {
                    rewritten.add(guarded);
                    anyChanged = true;
                } else {
                    rewritten.add(use);
                }
            }
            if (anyChanged) {
                return next.apply(new ActingInput(rewritten));
            }
        } catch (Exception e) {
            log.error("[GUARD] tool input guard failed (pass through), code={}", "TOOL_GUARD_ERROR", e);
        }
        return next.apply(input);
    }

    /** 返回改写后的工具调用；无改动则返回 null。 */
    ToolUseBlock guard(ToolUseBlock use) {
        if (use == null) {
            return null;
        }
        Map<String, Object> in = use.getInput() == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(use.getInput());
        boolean changed = false;

        for (Map.Entry<String, String> e : injectParams.entrySet()) {
            if (!in.containsKey(e.getKey())) {
                in.put(e.getKey(), e.getValue());
                changed = true;
            }
        }
        for (Map.Entry<String, Double> cap : numericCaps.entrySet()) {
            Double value = toDouble(in.get(cap.getKey()));
            if (value != null && value > cap.getValue()) {
                log.info("[GUARD] tool {} param {}={} exceeds cap {}, clamped",
                    use.getName(), cap.getKey(), value, cap.getValue());
                in.put(cap.getKey(), cap.getValue());
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        return new ToolUseBlock(use.getId(), use.getName(), in, use.getMetadata());
    }

    private Double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw != null) {
            try {
                return Double.parseDouble(raw.toString());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
