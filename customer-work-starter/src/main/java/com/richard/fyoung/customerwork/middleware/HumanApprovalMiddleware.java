package com.richard.fyoung.customerwork.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;

/**
 * 工具级人工确认中间件（2.0 Middleware，承接 1.x HumanApprovalHook，「Human-in-the-Loop」）。
 *
 * <p>落在 {@link #onActing} 段：对受控高风险工具（如 {@code submitRefund}）在执行前标记需人工复核。</p>
 *
 * <p><b>2.0 说明</b>：真正的"暂停 / 拒绝 / 人工放行"闸门已上升为框架原生的 <b>Permission System</b>
 * （见 {@code PermissionConfig} 的 ask/deny 规则），本中间件保留观测与告警职责，与 Permission 形成
 * "声明式权限 + 可观测确认"双层。异常不打断主链路。</p>
 * @author owlzhangfq@gmail.com
 */
public class HumanApprovalMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalMiddleware.class);

    private final Set<String> guardedTools;

    public HumanApprovalMiddleware(Set<String> guardedTools) {
        this.guardedTools = guardedTools;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (input.toolCalls() != null) {
            for (ToolUseBlock use : input.toolCalls()) {
                if (isGuarded(use.getName())) {
                    log.info("[HITL] high-risk tool flagged for human review: tool={} args={} "
                        + "(actual gate via Permission ask-rule)", use.getName(), use.getInput());
                }
            }
        }
        return next.apply(input);
    }

    boolean isGuarded(String toolName) {
        return toolName != null && guardedTools.contains(toolName);
    }
}
