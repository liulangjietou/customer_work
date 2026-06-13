package com.example.customerwork.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 工具级人工确认 Hook（对应进阶「Human-in-the-Loop」）。
 *
 * <p>对受控的高风险工具（如 {@code submitRefund} 涉资金操作）做"先执行、后暂停"的人工复核闸门：
 * 工具产生结果后，Hook 通过 {@link PostActingEvent#stopAgent()} 请求安全停止当前 ReAct 循环，
 * 把控制权交回人工坐席。框架会保留上下文与工具状态，人工确认后可无缝恢复。</p>
 *
 * <p>这是把"涉及资金操作必须人工确认"从提示词约定升级为框架级强约束——
 * 即使模型判断失误，闸门依然生效。</p>
 */
public class HumanApprovalHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalHook.class);

    private final Set<String> guardedTools;

    public HumanApprovalHook(Set<String> guardedTools) {
        this.guardedTools = guardedTools;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent e && isGuarded(e.getToolUse().getName())) {
            log.warn("[HITL] 高风险工具即将执行，已标记需人工复核: tool={} args={}",
                e.getToolUse().getName(), e.getToolUse().getInput());
        } else if (event instanceof PostActingEvent e && isGuarded(e.getToolUse().getName())) {
            // 受控工具执行完成后请求安全停止，交人工复核（保留上下文可恢复）
            e.stopAgent();
            log.warn("[HITL] 已暂停 Agent 等待人工确认: tool={}", e.getToolUse().getName());
        }
        return Mono.just(event);
    }

    private boolean isGuarded(String toolName) {
        return toolName != null && guardedTools.contains(toolName);
    }

    @Override
    public int priority() {
        // 高优先级：人工确认闸门应在普通观测 Hook 之前生效
        return 100;
    }
}
