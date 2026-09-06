package com.richard.fyoung.customerwork.core.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>在途请求登记探针</b>：实测框架是否真的把两条对话路径都纳入了优雅停机的等待范围。
 *
 * <p><b>为什么需要实测而不是读文档</b>：能力差距审计曾把「优雅停机是空转的——在途请求登记
 * 中间件从未装配，awaitTermination 恒立即返回」列为待办。核查框架源码后发现前提不成立：
 * 登记由 {@code AgentBase#runLifecycle} 承担，而 {@code ReActAgent#streamEvents} 也走同一个
 * runLifecycle（源码注释："Call runLifecycle directly — NOT call()"）。也就是说
 * <b>两条路径都自动登记，不需要额外装配任何东西</b>。</p>
 *
 * <p>但"读源码得出的结论"和"实际行为"是两件事，而这条结论直接决定了要不要动优雅停机链路。
 * 因此这里在模型桩里观察请求处理<b>进行中</b>的在途计数——那一刻计数大于 0，
 * 才真正说明这次调用被纳入了停机等待。</p>
 *
 * <p>这个测试同时是回归防线：框架哪天改了登记时机，它会红，提示重新评估优雅停机是否还完整。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class GracefulShutdownRequestTrackingProbeTest {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);
    private static final String STUB_REPLY = "好的。";

    /** 记录"模型被调用的那一刻"的在途请求数——那正是请求处理进行中的时刻。 */
    private final AtomicInteger observedInFlight = new AtomicInteger(-1);

    private Model probingModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                observedInFlight.set(GracefulShutdownManager.getInstance().getActiveRequestCount());
                ContentBlock text = TextBlock.builder().text(STUB_REPLY).build();
                return Flux.just(ChatResponse.builder()
                    .id(UUID.randomUUID().toString())
                    .content(List.of(text))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason("stop")
                    .build());
            }

            @Override
            public String getModelName() {
                return "shutdown-probe-model";
            }
        };
    }

    private ReActAgent agent() {
        return ReActAgent.builder()
            .name("shutdown-probe-agent")
            .sysPrompt("你是离线探针助手")
            .model(probingModel())
            .toolkit(new Toolkit())
            .maxIters(2)
            .build();
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("probe-user").sessionId("probe-session").build();
    }

    @Test
    @DisplayName("call 路径：处理进行中时在途计数大于 0")
    void callPathRegistersInFlightRequest() {
        observedInFlight.set(-1);

        Msg reply = agent().call("你好", ctx()).block(BLOCK_TIMEOUT);

        assertEquals(STUB_REPLY, reply == null ? null : reply.getTextContent());
        assertTrue(observedInFlight.get() > 0,
            "模型调用发生时在途计数应大于 0，实际 " + observedInFlight.get()
                + " —— 为 0 说明这次调用没被纳入停机等待，优雅停机会直接放过它");
    }

    /**
     * 流式路径是 H5 终端用户真正走的那条，必须同样被纳入。
     *
     * <p>本项目主链路是 WS 流式（ChatDispatchService → chatStream → streamEvents），
     * 只有 call() 被覆盖是不够的——那正是"能力只接在用户不走的那条路上"的形状。</p>
     */
    @Test
    @DisplayName("streamEvents 路径：处理进行中时在途计数同样大于 0")
    void streamEventsPathRegistersInFlightRequest() {
        observedInFlight.set(-1);

        Long events = agent().streamEvents("你好", ctx()).count().block(BLOCK_TIMEOUT);

        assertTrue(events != null && events > 0, "流式路径应产出事件");
        assertTrue(observedInFlight.get() > 0,
            "流式路径的在途计数应大于 0，实际 " + observedInFlight.get()
                + " —— H5 用户走的正是这条路，它不被登记就等于滚动发布时直接截断用户对话");
    }

    /** 请求结束后必须归零，否则停机会一直等到超时。 */
    @Test
    @DisplayName("请求结束后在途计数归零")
    void inFlightCountReturnsToZeroAfterCompletion() {
        agent().call("你好", ctx()).block(BLOCK_TIMEOUT);

        assertEquals(0, GracefulShutdownManager.getInstance().getActiveRequestCount(),
            "请求完成后必须注销，否则每次停机都要干等到超时");
    }
}
