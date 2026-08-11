package com.richard.fyoung.customerwork.core.middleware;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中间件触发行为固化探针（对照框架 issue #1941 / #1675）。
 *
 * <p>背景：本项目主链路 {@code CustomerServiceService.chat()} 走 {@code agent.call(userText, ctx)}，
 * 而 Masking / Observability / SelfCorrection 等中间件均挂在 onAgent 段。若 {@code ReActAgent.call()}
 * 不触发 onAgent（issue #1941 / #1675 反映的现象），则同步对话的脱敏与指标采集会静默失效。</p>
 *
 * <p>本测试用离线 stub Model 驱动一轮完整 ReAct 对话（模型返回纯文本、无工具调用，ReAct 循环判定为
 * 最终回复，一轮内收敛），用记录型 MiddlewareBase 记录 onAgent / onModelCall / onReasoning / onActing
 * 的实际调用次数，断言"验证到的事实"而非预期结论：无论触发与否测试都为绿，一旦框架行为变化（升级修复
 * issue）测试即失败，提醒我们重新评估主链路的脱敏/可观测假设。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MiddlewareInvocationVerificationTest {

    /** stub Model 固定回复文本，离线确定性。 */
    private static final String STUB_REPLY = "您好，这是离线固定回复";
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(20);

    /**
     * 记录型中间件：只统计各段被调用次数并透传事件，不改变任何行为。
     */
    private static final class RecordingMiddleware implements MiddlewareBase {
        final AtomicInteger onAgentCount = new AtomicInteger();
        final AtomicInteger onModelCallCount = new AtomicInteger();
        final AtomicInteger onReasoningCount = new AtomicInteger();
        final AtomicInteger onActingCount = new AtomicInteger();

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
            onAgentCount.incrementAndGet();
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            onModelCallCount.incrementAndGet();
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                            Function<ReasoningInput, Flux<AgentEvent>> next) {
            onReasoningCount.incrementAndGet();
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                         Function<ActingInput, Flux<AgentEvent>> next) {
            onActingCount.incrementAndGet();
            return next.apply(input);
        }
    }

    /**
     * 离线 stub Model：无条件返回一次 {@code generate_response} 工具调用，
     * ReAct 循环据此在一轮内产出最终回复，无需真实模型/网络。
     */
    private static Model stubModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                // 返回纯文本、无工具调用 => ReAct 循环判定为最终回复，一轮内收敛（离线确定性）
                ContentBlock text = TextBlock.builder().text(STUB_REPLY).build();
                ChatResponse resp = ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(text))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "stub-offline-model";
            }
        };
    }

    private ReActAgent buildAgent(RecordingMiddleware middleware) {
        return ReActAgent.builder()
            .name("probe-agent")
            .sysPrompt("你是离线探针助手")
            .model(stubModel())
            .toolkit(new Toolkit())
            .maxIters(3)
            .middleware(middleware)
            .build();
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("probe-user").sessionId("probe-session").build();
    }

    /**
     * 探针 1：经 {@code agent.call(userText, ctx)}（本项目主链路走法）驱动一轮对话。
     *
     * <p>实测结论（AgentScope 2.0.0-RC4 基线）：call() <b>会</b>触发 onAgent（1 次），
     * 未复现 issue #1941 / #1675 的"call() 不触发 onAgent"现象。即本项目挂在 onAgent 段的
     * Masking / Observability 等中间件在同步对话下能正常生效。</p>
     */
    @Test
    void call_shouldRecordActualMiddlewareInvocation() {
        RecordingMiddleware mw = new RecordingMiddleware();
        ReActAgent agent = buildAgent(mw);

        Msg reply = agent.call("你好", ctx()).block(BLOCK_TIMEOUT);
        assertTrue(reply != null && reply.getTextContent().contains(STUB_REPLY),
            "stub Model 应产出固定回复，确认一轮 ReAct 已完整跑通");

        // ---- 实测事实固化基线（对照 issue #1941 / #1675）----
        // 一旦断言失败，说明框架 call() 的中间件触发行为已变化，需重新评估主链路脱敏/可观测假设。
        assertEquals(1, mw.onAgentCount.get(),
            "探针固化：call() 下 onAgent 实测触发 1 次（issue #1941/#1675 未复现，中间件生效）");
        assertEquals(1, mw.onModelCallCount.get(),
            "探针固化：call() 下 onModelCall 实测触发 1 次");
        assertEquals(1, mw.onReasoningCount.get(),
            "探针固化：call() 下 onReasoning 实测触发 1 次");
    }

    /**
     * 探针 2：经 {@code agent.streamEvents(userText, ctx)}（流式对话路径）驱动一轮对话。
     *
     * <p>实测结论（AgentScope 2.0.0-RC4 基线）：streamEvents() 同样触发 onAgent（1 次），
     * 与 call() 路径一致。行为一并固化，对照 issue #1941 / #1675。</p>
     */
    @Test
    void stream_shouldRecordActualMiddlewareInvocation() {
        RecordingMiddleware mw = new RecordingMiddleware();
        ReActAgent agent = buildAgent(mw);

        Long events = agent.streamEvents("你好", ctx()).count().block(BLOCK_TIMEOUT);
        assertTrue(events != null && events > 0, "流式路径应至少产出一个事件");

        // ---- 实测事实固化基线（对照 issue #1941 / #1675）----
        assertEquals(1, mw.onAgentCount.get(),
            "探针固化：streamEvents() 下 onAgent 实测触发 1 次");
        assertEquals(1, mw.onModelCallCount.get(),
            "探针固化：streamEvents() 下 onModelCall 实测触发 1 次");
        assertEquals(1, mw.onReasoningCount.get(),
            "探针固化：streamEvents() 下 onReasoning 实测触发 1 次");
    }
}
