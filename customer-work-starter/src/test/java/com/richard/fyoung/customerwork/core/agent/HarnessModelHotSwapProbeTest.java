package com.richard.fyoung.customerwork.core.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架行为探针：<b>单例 {@code HarnessAgent} 热替换模型时，哪些调用方能被换过来</b>。
 *
 * <h3>要回答的问题</h3>
 * <p>AgentScope 2.x 下 Agent 字段全是 final，热替换模型（改 name / apiKey）常见两种思路：</p>
 * <ol>
 *   <li>在 {@code onModelCall} 中间件里把 {@code ModelCallInput.model()} 换成新模型往下传；</li>
 *   <li>构建时注入一个委托壳，配置变更时只替换壳内的引用。</li>
 * </ol>
 * <p>反编译 2.0.3 可见 {@code CompactionMiddleware} 在构造期把模型存成 final 字段，并直接交给
 * {@code ConversationCompactor} 生成摘要——这条调用<b>不经过</b> {@code onModelCall}。
 * 本测试把这个字节码层面的推断落成运行时事实。</p>
 *
 * <h3>红了说明什么</h3>
 * <ul>
 *   <li>{@link #onModelCallSwap_doesNotReachCompaction()} 红：框架让压缩摘要也走了
 *       {@code onModelCall}，思路 1 从"只换了一半"变成可用，复核后可改写该断言，不要直接删；</li>
 *   <li>{@link #delegatingModel_swapsMainReasoningAndCompactionTogether()} 红：委托壳这条正路失效了，
 *       通常是框架开始在模型上做 {@code instanceof} 判断或绕开了注入的模型，需重新评估热替换方案。</li>
 * </ul>
 *
 * <p>离线：两个桩模型只返回纯文本，ReAct 一轮即收敛；关闭记忆钩子与压缩前刷写，
 * 确保"非中间件路径的模型调用"只剩压缩摘要这一条，归因无歧义。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class HarnessModelHotSwapProbeTest {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(30);

    /** 足够让消息数越过压缩阈值的轮次 */
    private static final int TURNS = 6;

    private static final int TRIGGER_MESSAGES = 4;

    private static final int KEEP_MESSAGES = 1;

    @TempDir
    Path workspace;

    @Test
    void onModelCallSwap_doesNotReachCompaction() {
        CountingModel original = new CountingModel("original-model");
        CountingModel replacement = new CountingModel("replacement-model");
        MiddlewareBase swapMiddleware = new MiddlewareBase() {
            @Override
            public int order() {
                return 0;
            }

            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                                Function<ModelCallInput, Flux<AgentEvent>> next) {
                return next.apply(new ModelCallInput(input.messages(), input.tools(), input.options(), replacement));
            }
        };

        try (HarnessAgent agent = buildAgent(original, swapMiddleware)) {
            runTurns(agent, "swap-by-middleware");
        }

        assertEquals(TURNS, replacement.calls.get(),
            "主推理每轮一次，应全部被 onModelCall 换到新模型");
        assertTrue(original.calls.get() > 0,
            "期望压缩摘要仍打到构建时注入的旧模型（onModelCall 换模型只覆盖主推理），实际旧模型 0 次调用。"
                + "先确认压缩是否仍被触发（另一个用例同时红即为此因）；若压缩照常触发，"
                + "说明框架已让压缩走 onModelCall，复核后改写断言");
    }

    @Test
    void delegatingModel_swapsMainReasoningAndCompactionTogether() {
        CountingModel original = new CountingModel("original-model");
        CountingModel replacement = new CountingModel("replacement-model");
        RefreshableModel refreshable = new RefreshableModel(original);
        // 模拟配置中心回调：构建之后、首轮对话之前完成替换
        refreshable.refresh(replacement);

        try (HarnessAgent agent = buildAgent(refreshable, null)) {
            runTurns(agent, "swap-by-delegate");
        }

        assertEquals(0, original.calls.get(),
            "替换之后旧模型不应再收到任何调用（主推理与压缩摘要都不应）");
        assertTrue(replacement.calls.get() > TURNS,
            "新模型应同时承接主推理（" + TURNS + " 次）与压缩摘要（至少 1 次），实际 "
                + replacement.calls.get());
    }

    private HarnessAgent buildAgent(Model model, MiddlewareBase extraMiddleware) {
        ReActAgent inner = ReActAgent.builder()
            .name("model-hot-swap-probe")
            .model(model)
            .toolkit(new Toolkit())
            .stateStore(new InMemoryAgentStateStore())
            .build();
        HarnessAgent.Builder builder = HarnessAgent.Builder.fromAgent(inner)
            .workspace(workspace)
            .disableMemoryHooks()
            .disableMemoryTools()
            .compaction(CompactionConfig.builder()
                .triggerMessages(TRIGGER_MESSAGES)
                .keepMessages(KEEP_MESSAGES)
                .flushBeforeCompact(false)
                .build());
        if (extraMiddleware != null) {
            builder.middleware(extraMiddleware);
        }
        return builder.build();
    }

    private void runTurns(HarnessAgent agent, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder().userId("probe-user").sessionId(sessionId).build();
        for (int i = 0; i < TURNS; i++) {
            agent.call("第 " + i + " 轮问题", ctx).block(BLOCK_TIMEOUT);
        }
    }

    /** 只记调用次数、返回纯文本的桩模型 */
    private static final class CountingModel implements Model {

        private final String name;

        private final AtomicInteger calls = new AtomicInteger();

        private CountingModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.incrementAndGet();
            ContentBlock text = TextBlock.builder().text(name + " reply").build();
            return Flux.just(ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(List.of(text))
                .usage(new ChatUsage(1, 1, 0.0))
                .finishReason("stop")
                .build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    /**
     * 可热替换的模型委托壳：所有持有模型引用的地方拿到的都是它，替换内部引用即全部生效。
     * 带 default 实现的接口方法也必须转发，否则编译通过但静默返回默认值。
     */
    private static final class RefreshableModel implements Model {

        private final AtomicReference<Model> delegate;

        private RefreshableModel(Model initial) {
            this.delegate = new AtomicReference<>(initial);
        }

        private void refresh(Model newModel) {
            delegate.set(newModel);
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            // 订阅时才取引用：在途流用旧模型，下一次调用用新模型
            return Flux.defer(() -> delegate.get().stream(messages, tools, options));
        }

        @Override
        public String getModelName() {
            return delegate.get().getModelName();
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return delegate.get().supportsNativeStructuredOutput();
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return delegate.get().supportsNativeStructuredOutputWithTools();
        }

        @Override
        public int getContextWindowSize() {
            return delegate.get().getContextWindowSize();
        }
    }
}
