package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
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
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * VibeCoding 沙箱命令护栏：挂在 {@link MiddlewareBase#onActing}，工具真正执行前对字符串入参
 * 匹配破坏性命令模式，命中即改写为安全占位（{@link AdminSandboxProperties.Guard#DESTRUCTIVE_PLACEHOLDER}），
 * 不抛异常打断主链路。
 *
 * <p>与 {@code customer-work-spring-boot-starter} 的 {@code ToolGuardMiddleware} 同一手法
 * （破坏性命令拦截那部分），但配置源换成本模块自己的 {@link AdminSandboxProperties}——admin-server
 * 已经排除了 starter 的自动装配（见 {@code application.yml} 的 {@code spring.autoconfigure.exclude}），
 * 不复用 starter 的 {@code CustomerWorkProperties} 配置体系。只做破坏性命令拦截这一项，starter 里
 * "公共参数注入"“数值上限钳制”是客服业务场景特有的，VibeCoding 不需要。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SandboxGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SandboxGuardMiddleware.class);
    private static final String CODE_DESTRUCTIVE = "SANDBOX-GUARD-DESTRUCTIVE";

    private final boolean enabled;
    /** 破坏性命令判定复用 {@link SandboxRiskDetector}（与 HITL 同源，规则只在一处维护）。 */
    private final SandboxRiskDetector riskDetector;
    /** 命中破坏性入参的累计次数（可观测用途，供单测断言）。 */
    private final LongAdder destructiveHits = new LongAdder();

    public SandboxGuardMiddleware(AdminSandboxProperties properties, SandboxRiskDetector riskDetector) {
        this.enabled = properties.getGuard().isEnabled();
        this.riskDetector = riskDetector;
    }

    /** 命中破坏性入参的累计次数（供单测断言）。 */
    long destructiveHitCount() {
        return destructiveHits.sum();
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
            log.error("[workspace] sandbox guard failed (pass through), code={}", "SANDBOX_GUARD_ERROR", e);
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
        for (Map.Entry<String, Object> entry : new LinkedHashMap<>(in).entrySet()) {
            Object raw = entry.getValue();
            if (raw instanceof CharSequence && riskDetector.matchesDestructive(raw.toString())) {
                destructiveHits.increment();
                log.error("[workspace] sandbox tool {} param {} hit destructive pattern, rewritten, code={}",
                    use.getName(), entry.getKey(), CODE_DESTRUCTIVE);
                in.put(entry.getKey(), AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER);
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }
        return new ToolUseBlock(use.getId(), use.getName(), in, use.getMetadata());
    }
}
