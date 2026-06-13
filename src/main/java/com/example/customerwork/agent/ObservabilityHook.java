package com.example.customerwork.agent;

import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 可观测 Hook（对应深度解析一文⑥"全链路可观测与数据飞轮"的数据采集入口）。
 *
 * <p>在 Agent 生命周期的关键节点打点：推理开始、工具调用前后、单次请求结束（含 token 用量）、
 * 异常。这些正是数据飞轮所需的全链路信号（输入、Prompt、模型输出、时延、成本）。</p>
 *
 * <p>生产落地：把这里的 {@code log} 替换为上报 OpenTelemetry Span / 指标系统，
 * 再供 RM Gallery 评估与 Trinity-RFT 强化学习消费。本类是采集点，不内置可观测后端。</p>
 *
 * <p>Hook 必须只读透传事件：观测逻辑不得抛出异常打断 Agent 主链路，因此整体包了兜底。</p>
 */
public class ObservabilityHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            record(event);
        } catch (Exception e) {
            // 观测失败绝不能影响业务主链路
            log.warn("[OTEL] 观测打点异常（已忽略）: {}", e.getMessage());
        }
        // 不修改事件，仅观测；务必把原 event 透传下去
        return Mono.just(event);
    }

    private void record(HookEvent event) {
        // Java 17 兼容写法：if-instanceof 模式匹配
        if (event instanceof PreReasoningEvent e) {
            log.info("[OTEL] 推理开始 agent={} model={}", e.getAgent().getName(), e.getModelName());
        } else if (event instanceof PreActingEvent e) {
            log.info("[OTEL] 工具调用开始 tool={} args={}",
                e.getToolUse().getName(), e.getToolUse().getInput());
        } else if (event instanceof PostActingEvent e) {
            log.info("[OTEL] 工具调用结束 tool={} stopRequested={}",
                e.getToolUse().getName(), e.isStopRequested());
        } else if (event instanceof PostCallEvent e) {
            logUsage(e.getFinalMessage());
        } else if (event instanceof ErrorEvent e) {
            log.error("[OTEL] Agent 执行异常 agent={}",
                e.getAgent().getName(), e.getError());
        }
    }

    private void logUsage(Msg finalMessage) {
        if (finalMessage == null) {
            return;
        }
        ChatUsage usage = finalMessage.getChatUsage();
        if (usage != null) {
            log.info("[OTEL] 请求结束 inputTokens={} outputTokens={} totalTokens={} time={}s",
                usage.getInputTokens(), usage.getOutputTokens(),
                usage.getTotalTokens(), usage.getTime());
        } else {
            log.info("[OTEL] 请求结束（无 token 用量信息）");
        }
    }

    @Override
    public int priority() {
        // 低优先级：日志 / 指标类 Hook，在业务 Hook 之后执行
        return 800;
    }
}
