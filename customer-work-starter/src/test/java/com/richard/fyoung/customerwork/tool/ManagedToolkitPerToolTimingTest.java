package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolRequestConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManagedToolkit} 串行模式下逐工具回调 {@link ToolCallObserver}，以及它依赖的框架契约。
 *
 * <p>背景：框架事件流里的 {@code ToolResultStartEvent} / {@code ToolResultEndEvent} 都在批次边界一次性发出，
 * 无法从事件流算出单个工具耗时，所以计时点放在 {@code Toolkit.callTools}。</p>
 * @author owlzhangfq@gmail.com
 */
class ManagedToolkitPerToolTimingTest {

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(10);

    /** 一个慢工具、一个快工具：耗时差足够大，能区分「各自计时」与「整批之和」。 */
    public static class TimingTools {
        @Tool(description = "慢工具")
        public Mono<String> slowTool(@ToolParam(name = "q", description = "q") String q) {
            return Mono.delay(Duration.ofMillis(200)).thenReturn("slow:" + q);
        }

        @Tool(description = "快工具")
        public Mono<String> fastTool(@ToolParam(name = "q", description = "q") String q) {
            return Mono.just("fast:" + q);
        }
    }

    private record Observed(String id, String name, long durationMs, Throwable error) {
    }

    private static ManagedToolkit toolkit() {
        ManagedToolkit toolkit = new ManagedToolkit();
        toolkit.registerTool(new TimingTools());
        return toolkit;
    }

    private static List<ToolUseBlock> batch() {
        return List.of(
            new ToolUseBlock("c1", "slowTool", Map.of("q", "a")),
            new ToolUseBlock("c2", "fastTool", Map.of("q", "b")));
    }

    private static RuntimeContext ctxWith(List<Observed> sink) {
        RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s").build();
        ctx.put(ToolCallObserver.class, (call, result, startMs, durationNanos, error) ->
            sink.add(new Observed(call.getId(), call.getName(), durationNanos / 1_000_000L, error)));
        return ctx;
    }

    @Test
    void sequentialWithObserver_shouldTimeEachToolSeparatelyAndKeepOrder() {
        List<Observed> observed = new CopyOnWriteArrayList<>();
        List<ToolResultBlock> results = toolkit()
            .callTools(batch(), null, null, ctxWith(observed))
            .block(BLOCK_TIMEOUT);

        assertEquals(2, results.size(), "拆批后返回的结果数要与入参一致");
        assertEquals("c1", results.get(0).getId(), "结果顺序必须与入参一致，框架按下标配对");
        assertEquals("c2", results.get(1).getId());

        assertEquals(List.of("slowTool", "fastTool"), observed.stream().map(Observed::name).toList(),
            "每个工具各回调一次，且按执行顺序");
        Observed slow = observed.get(0);
        Observed fast = observed.get(1);
        assertTrue(slow.durationMs() >= 180, "慢工具耗时是它自己的：" + observed);
        assertTrue(fast.durationMs() < slow.durationMs() - 150,
            "快工具不能分到慢工具的耗时，否则就还是整批之和：" + observed);
        assertNull(slow.error());
    }

    @Test
    void withoutObserver_shouldNotChangeBehavior() {
        List<ToolResultBlock> results = toolkit()
            .callTools(batch(), null, null, RuntimeContext.builder().userId("u").build())
            .block(BLOCK_TIMEOUT);
        assertEquals(2, results.size());
        assertEquals("c1", results.get(0).getId());
    }

    @Test
    void parallelMode_shouldNotSplitBatch() {
        List<Observed> observed = new CopyOnWriteArrayList<>();
        ManagedToolkit toolkit = new ManagedToolkit(ToolkitConfig.builder().parallel(true).build());
        toolkit.registerTool(new TimingTools());

        List<ToolResultBlock> results = toolkit.callTools(batch(), null, null, ctxWith(observed))
            .block(BLOCK_TIMEOUT);

        assertEquals(2, results.size());
        assertTrue(observed.isEmpty(), "并行时各工具同时跑，逐个计时不成立，原样交给框架");
    }

    @Test
    void observerFailure_mustNotFailToolCall() {
        RuntimeContext ctx = RuntimeContext.builder().userId("u").build();
        ctx.put(ToolCallObserver.class, (call, result, startMs, durationNanos, error) -> {
            throw new IllegalStateException("observer boom");
        });
        List<ToolResultBlock> results = toolkit().callTools(batch(), null, null, ctx).block(BLOCK_TIMEOUT);
        assertEquals(2, results.size(), "计时失败不能让工具调用失败");
    }

    /**
     * 框架契约门禁：逐工具计时依赖 {@code Toolkit} 有一个 public、非 final 的 6 参 {@code callTools}，
     * 且 ReActAgent 调的正是它。框架升级改掉签名时，覆写会静默失效、报表退回批次级而不报错——在这里红。
     */
    @Test
    void frameworkContract_sixArgCallToolsIsOverridable() throws Exception {
        Method method = Toolkit.class.getMethod("callTools", List.class, ExecutionConfig.class,
            Agent.class, RuntimeContext.class, ToolRequestConfig.class, BiConsumer.class);
        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertFalse(Modifier.isFinal(method.getModifiers()), "被改成 final 后覆写会编译失败或失效");
        assertEquals(ManagedToolkit.class, ManagedToolkit.class.getMethod("callTools", List.class,
                ExecutionConfig.class, Agent.class, RuntimeContext.class, ToolRequestConfig.class,
                BiConsumer.class).getDeclaringClass(),
            "ManagedToolkit 必须覆写这个重载");
    }
}
