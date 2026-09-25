package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheService;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.middleware.LoopGuardMiddleware;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 轮次用尽的答复进不进语义缓存——用真实 {@link ReActAgent} 与循环守卫验证。
 *
 * <p><b>为什么要真实跑一遍</b>：结束原因由框架标在收尾消息上、再由循环守卫原样交出去（PR #244 之前它会被
 * 重建消息抹成正常结束）。手搓一个带 {@code MAX_ITERATIONS} 的最终结果，只能证明「看到了就不缓存」，
 * 证明不了「真实链路上看得到」。被缓存的收尾带着「已为您转接人工客服」，命中时却不会真的转接。</p>
 *
 * <p><b>对照组同样重要</b>：框架正常收尾时<b>不写</b>结束原因，判定靠的是 getter 对缺失键的默认值。
 * 哪天有人把判定改成「只认显式写入的原因」，或者框架改了收尾的发出方式，缓存会一条都写不进去、
 * 且不报任何错——对照组会先红。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceCacheRealAgentTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long ASYNC_WRITE_GRACE_MS = 200;
    private static final long ASYNC_WRITE_TIMEOUT_MS = 2000;
    private static final int MAX_ITERS = 2;
    private static final String SESSION = "u1:conv-cache-finish";
    private static final String QUESTION = "运费怎么算";
    private static final String TOOL_NAME = "queryOrder";
    /** 每一轮推理都先说一句再调工具，永不收敛。 */
    private static final String THINKING_ALOUD = "我先帮您查一下。";
    /** 框架的收尾调用不带工具清单，据此分片吐出收尾回复——真实模型的收尾也是流式的。 */
    private static final List<String> SUMMARY_CHUNKS = List.of("抱歉，", "我暂时没能", "处理好这个问题。");
    private static final String SUMMARY = String.join("", SUMMARY_CHUNKS);
    private static final List<String> ANSWER_CHUNKS = List.of("运费", "满 99 包邮。");
    private static final String ANSWER = String.join("", ANSWER_CHUNKS);
    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();
    private static final SemanticCacheService.CacheGeneration CACHE_GENERATION =
        new SemanticCacheService.CacheGeneration("tenant", "test-generation", true);

    private SemanticCacheService cache;
    private HandoffService handoffService;

    /** 离线工具：真实注册进 Toolkit，被 ReAct 循环真实调用。 */
    public static class OrderTools {
        @Tool(description = "查询订单状态。用户问订单进度时调用。")
        public Mono<String> queryOrder(@ToolParam(name = "orderId", description = "订单号") String orderId) {
            return Mono.just("订单 " + orderId + " 的状态暂时无法确认");
        }
    }

    @BeforeEach
    void setUp() {
        cache = mock(SemanticCacheService.class);
        handoffService = mock(HandoffService.class);
        when(cache.captureGeneration()).thenReturn(CACHE_GENERATION);
        when(cache.lookup(eq(CACHE_GENERATION), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("流式：轮次用尽的收尾与转人工说明照常到达用户，但不写缓存")
    void streamExhaustedReply_shouldNotBeCached() {
        CustomerServiceService service = serviceWith(agent(loopingModel()));

        String onScreen = String.join("", service.chatStream(SESSION, QUESTION).collectList().block(TIMEOUT));

        assertTrue(onScreen.endsWith(SUMMARY + NOTICE), "收尾与去向说明应照常到达用户：" + onScreen);
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
        assertNotCached();
    }

    @Test
    @DisplayName("非流式：轮次用尽的收尾照常返回，但不写缓存")
    void callExhaustedReply_shouldNotBeCached() {
        CustomerServiceService service = serviceWith(agent(loopingModel()));

        String reply = service.chat(SESSION, QUESTION).block(TIMEOUT);

        assertEquals(SUMMARY + NOTICE, reply);
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
        assertNotCached();
    }

    @Test
    @DisplayName("对照：同一套装配下正常收尾的答复，两条路径都写缓存")
    void normallyFinishedReply_shouldBeCachedOnBothPaths() {
        CustomerServiceService service = serviceWith(agent(answeringModel()));

        assertEquals(ANSWER, String.join("", service.chatStream(SESSION, QUESTION).collectList().block(TIMEOUT)));
        assertEquals(ANSWER, service.chat(SESSION, QUESTION).block(TIMEOUT));

        verify(cache, timeout(ASYNC_WRITE_TIMEOUT_MS).times(2))
            .put(eq(CACHE_GENERATION), eq(SESSION), eq(QUESTION), eq(ANSWER));
        verify(handoffService, never()).create(anyString(), anyString());
    }

    // ---------- 辅助 ----------

    private void assertNotCached() {
        verify(cache, after(ASYNC_WRITE_GRACE_MS).never())
            .put(any(SemanticCacheService.CacheGeneration.class), anyString(), anyString(), anyString());
    }

    private CustomerServiceService serviceWith(ReActAgent agent) {
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        return new CustomerServiceService(factory, mock(SessionStateManager.class), new CustomerWorkProperties(),
            empty(), empty(), empty(), empty(), provider(cache), empty());
    }

    private ReActAgent agent(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        return ReActAgent.builder()
            .name("cache-finish-probe")
            .sysPrompt("你是电商客服助手，查订单时调用工具。")
            .model(model)
            .toolkit(toolkit)
            .maxIters(MAX_ITERS)
            .middleware(new LoopGuardMiddleware(new CustomerWorkProperties(), provider(handoffService),
                provider((AuditSink) null), provider((MeterRegistry) new SimpleMeterRegistry())))
            .build();
    }

    /** 带工具清单的推理调用一律「先说一句再调工具」，直到轮次用尽；收尾调用分片吐出收尾回复。 */
    private static Model loopingModel() {
        AtomicInteger calls = new AtomicInteger();
        return scriptedModel("stub-looping-model", (tools) -> {
            if (tools == null || tools.isEmpty()) {
                return Flux.fromIterable(SUMMARY_CHUNKS)
                    .map(chunk -> response(List.of(TextBlock.builder().text(chunk).build()), "stop"));
            }
            int n = calls.incrementAndGet();
            return Flux.just(response(List.of(
                TextBlock.builder().text(THINKING_ALOUD).build(),
                new ToolUseBlock("call-" + n, TOOL_NAME, Map.of("orderId", "SO-" + n))), "tool_calls"));
        });
    }

    /** 不调工具、直接分片作答：第一轮就正常收尾。 */
    private static Model answeringModel() {
        return scriptedModel("stub-answering-model", (tools) -> Flux.fromIterable(ANSWER_CHUNKS)
            .map(chunk -> response(List.of(TextBlock.builder().text(chunk).build()), "stop")));
    }

    private static Model scriptedModel(String name,
                                       Function<List<ToolSchema>, Flux<ChatResponse>> script) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return script.apply(tools);
            }

            @Override
            public String getModelName() {
                return name;
            }
        };
    }

    private static ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder()
            .id(UUID.randomUUID().toString())
            .content(content)
            .usage(new ChatUsage(1, 1, 0.0))
            .finishReason(finishReason)
            .build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
