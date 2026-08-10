package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 租户上下文中间件（2.0 Middleware 第五段 {@link #onSystemPrompt}）。
 *
 * <p>在每次调用前，把当前 {@link RuntimeContext} 的租户（userId）与会话（sessionId）作为运行时上下文
 * 追加到系统提示词尾部，让模型在多租户场景下感知"我在为哪个租户/会话服务"，便于做租户化口径与隔离。
 * 这是 1.x 扁平 Hook 无法干净表达、而 2.0 Middleware {@code onSystemPrompt} 段天然支持的能力。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class TenantContextMiddleware implements MiddlewareBase {

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (ctx == null || (ctx.getUserId() == null && ctx.getSessionId() == null)) {
            return Mono.just(currentPrompt);
        }
        String tenantNote = String.format(
            "%n%n[运行时上下文] 当前租户=%s，会话=%s。请在该租户口径下作答，不得泄露其它租户信息。",
            ctx.getUserId() == null ? "default" : ctx.getUserId(),
            ctx.getSessionId() == null ? "default" : ctx.getSessionId());
        return Mono.just((currentPrompt == null ? "" : currentPrompt) + tenantNote);
    }
}
