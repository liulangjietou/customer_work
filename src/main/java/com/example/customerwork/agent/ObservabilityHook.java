package com.example.customerwork.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 可观测 Hook（对应流程图⑥"全链路可观测与数据飞轮"的数据采集入口）。
 *
 * <p>Hook 是 AgentScope 拦截 Agent 生命周期的标准扩展点。这里在推理前、工具调用前后打点，
 * 把模型名、工具名等关键信息记录下来。</p>
 *
 * <p>生产落地时，把这里的 {@code log.info} 替换为：上报 OpenTelemetry Span、写入可观测系统，
 * 进而供 RM Gallery 评估、Trinity-RFT 强化学习消费——这就是"数据飞轮"的起点。
 * 本类只演示采集点，不内置完整可观测后端（那属于工程化范畴）。</p>
 */
public class ObservabilityHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // Java 17 兼容写法：if-instanceof 模式匹配，不使用 Java 21 的 switch 模式
        if (event instanceof PreReasoningEvent e) {
            log.info("[OTEL] 推理开始 model={}", e.getModelName());
        } else if (event instanceof PreActingEvent e) {
            log.info("[OTEL] 工具调用开始 tool={}", e.getToolUse().getName());
        } else if (event instanceof PostActingEvent e) {
            log.info("[OTEL] 工具调用结束 tool={}", e.getToolUse().getName());
        }
        // 不修改事件，仅观测；务必把原 event 透传下去
        return Mono.just(event);
    }

    @Override
    public int priority() {
        // 低优先级：日志/指标类 Hook
        return 800;
    }
}
