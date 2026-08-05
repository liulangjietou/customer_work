package com.richard.fyoung.customeradmin.workspace.runtime;

import com.richard.fyoung.customeradmin.config.AdminSandboxProperties;
import com.richard.fyoung.customerwork.middleware.ToolCallRewriteCore;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * VibeCoding 沙箱命令护栏：挂在 {@link MiddlewareBase#onActing}，工具真正执行前对字符串入参
 * 匹配破坏性命令模式，命中即改写为安全占位（{@link AdminSandboxProperties.Guard#DESTRUCTIVE_PLACEHOLDER}），
 * 不抛异常打断主链路。
 *
 * <p><b>改写动作不在本类</b>：onActing 骨架与"命中改写 + 计数告警"是 starter 的
 * {@link ToolCallRewriteCore}（与 {@code ToolGuardMiddleware} 同一份实现）。本类只做两件事：
 * 接本模块自己的开关/占位配置（admin-server 排除了 starter 自动装配，不复用 {@code CustomerWorkProperties}），
 * 以及把破坏性判定委托给 {@link SandboxRiskDetector}——与 HITL 同源，规则只在一处维护。</p>
 *
 * <p>只做破坏性命令拦截这一项，starter 里"公共参数注入""数值上限钳制"是客服业务场景特有的，
 * VibeCoding 不需要。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class SandboxGuardMiddleware implements MiddlewareBase {

    /** 日志前缀（告警关键字，勿改）。 */
    private static final String LOG_SCOPE = "[workspace] sandbox";
    private static final String CODE_DESTRUCTIVE = "SANDBOX-GUARD-DESTRUCTIVE";
    private static final String CODE_GUARD_FAIL = "SANDBOX_GUARD_ERROR";

    private final boolean enabled;
    private final ToolCallRewriteCore rewriteCore;

    public SandboxGuardMiddleware(AdminSandboxProperties properties, SandboxRiskDetector riskDetector) {
        this.enabled = properties.getGuard().isEnabled();
        this.rewriteCore = new ToolCallRewriteCore(riskDetector::matchesDestructive,
            AdminSandboxProperties.Guard.DESTRUCTIVE_PLACEHOLDER,
            LOG_SCOPE, CODE_DESTRUCTIVE, CODE_GUARD_FAIL);
    }

    /** 命中破坏性入参的累计次数（供单测断言）。 */
    long destructiveHitCount() {
        return rewriteCore.destructiveHitCount();
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (!enabled) {
            return next.apply(input);
        }
        return rewriteCore.guardActing(input, next, this::guard);
    }

    /** 返回改写后的工具调用；无改动则返回 null。 */
    ToolUseBlock guard(ToolUseBlock use) {
        return rewriteCore.guardDestructive(use);
    }
}
