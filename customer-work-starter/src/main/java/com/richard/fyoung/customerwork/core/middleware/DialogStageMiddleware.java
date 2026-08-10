package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.dialog.DialogStage;
import com.richard.fyoung.customerwork.capability.dialog.DialogStageService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 对话阶段中间件（2.0 Middleware 第五段 {@link #onSystemPrompt}，借鉴 AliGo 动态 Prompt 状态机）。
 *
 * <p>按会话当前 {@link DialogStage} 把"聚焦当前主链路"的指令片段动态追加到系统提示词，替代把全量规则
 * 塞进一个静态大 Prompt——降低 token、提升当前阶段的判定准确率。与 {@code TenantContextMiddleware}
 * 同段叠加，互不影响。</p>
 * @author owlzhangfq@gmail.com
 */
@Component
public class DialogStageMiddleware implements MiddlewareBase {

    private final DialogStageService stageService;

    public DialogStageMiddleware(DialogStageService stageService) {
        this.stageService = stageService;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String sessionId = ctx == null ? null : ctx.getSessionId();
        DialogStage stage = stageService.current(sessionId);
        String note = String.format("%n%n[对话阶段] 当前处于%s", stage.promptFragment());
        return Mono.just((currentPrompt == null ? "" : currentPrompt) + note);
    }
}
