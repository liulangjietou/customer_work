package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.capability.semanticcache.SemanticCacheService;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.middleware.LoopGuardMiddleware;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.tool.HumanHandoffTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
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
import java.util.function.BiFunction;

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
 * 轮次用尽、或本轮转过人工的答复进不进语义缓存——用真实 {@link ReActAgent}、循环守卫与转人工工具验证。
 *
 * <p><b>为什么要真实跑一遍</b>：结束原因由框架标在收尾消息上、再由循环守卫原样交出去（PR #244 之前它会被
 * 重建消息抹成正常结束）。手搓一个带 {@code MAX_ITERATIONS} 的最终结果，只能证明「看到了就不缓存」，
 * 证明不了「真实链路上看得到」。被缓存的收尾带着「已为您转接人工客服」，命中时却不会真的转接。</p>
 *
 * <p><b>转人工工具那组</b>是正常收尾（{@code MODEL_STOP}），结束原因挡不住；要证明的是工具在真实 ReAct
 * 循环里建单时，服务端在 Agent 运行期间打开的观察窗口确实记得到——而不是只在手搓的事件流里记得到。</p>
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
    /** 用户在咨询类问题里要求转人工——意图路由仍判为 consult，缓存闸门的意图白名单挡不住它。 */
    private static final String CONSULT_WITH_HANDOFF = "运费政策太坑了，给我转人工";
    private static final String TRANSFER_TOOL = "transferToHuman";
    private static final String HANDOFF_REASON = "用户要求人工";
    private static final List<String> HANDOFF_CHUNKS = List.of("已为您转接人工客服，", "请稍候。");
    private static final String HANDOFF_ANNOUNCED = String.join("", HANDOFF_CHUNKS);
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

    /**
     * 模型自己调转人工工具——五个转人工来源里最常见的一个，收尾是正常结束（{@code MODEL_STOP}），
     * 光看结束原因会把「已为您转接人工客服」收进缓存；命中时 Agent 不运行，下一个人收到这句话却没有任何转接。
     */
    @Test
    @DisplayName("流式：模型调了转人工工具的答复照常到达用户，工单真实建出，但不写缓存")
    void streamReplyAfterTransferTool_shouldNotBeCached() {
        HandoffService handoffs = new HandoffService();
        CustomerServiceService service = serviceWith(agentWithTransferTool(transferringModel(), handoffs), handoffs);

        String onScreen = String.join("",
            service.chatStream(SESSION, CONSULT_WITH_HANDOFF).collectList().block(TIMEOUT));

        assertEquals(HANDOFF_ANNOUNCED, onScreen);
        assertHandedOff(handoffs);
        assertNotCached();
    }

    @Test
    @DisplayName("非流式：模型调了转人工工具的答复照常返回，工单真实建出，但不写缓存")
    void callReplyAfterTransferTool_shouldNotBeCached() {
        HandoffService handoffs = new HandoffService();
        CustomerServiceService service = serviceWith(agentWithTransferTool(transferringModel(), handoffs), handoffs);

        assertEquals(HANDOFF_ANNOUNCED, service.chat(SESSION, CONSULT_WITH_HANDOFF).block(TIMEOUT));
        assertHandedOff(handoffs);
        assertNotCached();
    }

    @Test
    @DisplayName("对照：挂着转人工工具但模型没调它，正常收尾的答复两条路径都写缓存")
    void transferToolAvailableButUnused_shouldBeCachedOnBothPaths() {
        HandoffService handoffs = new HandoffService();
        CustomerServiceService service = serviceWith(agentWithTransferTool(answeringModel(), handoffs), handoffs);

        assertEquals(ANSWER, String.join("", service.chatStream(SESSION, QUESTION).collectList().block(TIMEOUT)));
        assertEquals(ANSWER, service.chat(SESSION, QUESTION).block(TIMEOUT));

        verify(cache, timeout(ASYNC_WRITE_TIMEOUT_MS).times(2))
            .put(eq(CACHE_GENERATION), eq(SESSION), eq(QUESTION), eq(ANSWER));
        assertTrue(handoffs.list().isEmpty(), "对照组不该建出任何转人工单");
    }

    // ---------- 辅助 ----------

    private static void assertHandedOff(HandoffService handoffs) {
        List<HandoffTicket> tickets = handoffs.list();
        assertEquals(1, tickets.size(), "转人工工具应真实建出一张工单：" + tickets);
        assertEquals(SESSION, tickets.get(0).getSessionId());
    }

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

    private CustomerServiceService serviceWith(ReActAgent agent, HandoffService handoffs) {
        CustomerServiceService service = serviceWith(agent);
        service.setHandoffService(handoffs);
        return service;
    }

    /** 与生产同一个转人工工具，绑定真实会话号（生产由 ToolRegistrar 在建 Agent 时传入）。 */
    private static ReActAgent agentWithTransferTool(Model model, HandoffService handoffs) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new HumanHandoffTools(handoffs, null, SESSION));
        return ReActAgent.builder()
            .name("cache-handoff-probe")
            .sysPrompt("你是电商客服助手，用户要求人工时调用转人工工具。")
            .model(model)
            .toolkit(toolkit)
            .build();
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
        return scriptedModel("stub-looping-model", (messages, tools) -> {
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
        return scriptedModel("stub-answering-model", (messages, tools) -> Flux.fromIterable(ANSWER_CHUNKS)
            .map(chunk -> response(List.of(TextBlock.builder().text(chunk).build()), "stop")));
    }

    /** 本轮还没拿到工具结果时调转人工工具；拿到之后分片宣告已转接，正常收尾。 */
    private static Model transferringModel() {
        return scriptedModel("stub-transferring-model", (messages, tools) -> {
            boolean transferred = messages.stream().anyMatch(msg -> msg.hasContentBlocks(ToolResultBlock.class));
            if (!transferred) {
                // 框架按原始 JSON（content）校验与绑定参数，只给 input 会校验失败、工具根本不执行
                return Flux.just(response(List.of(ToolUseBlock.builder().id("call-handoff").name(TRANSFER_TOOL)
                    .input(Map.of("reason", HANDOFF_REASON)).content("{\"reason\":\"" + HANDOFF_REASON + "\"}")
                    .build()), "tool_calls"));
            }
            return Flux.fromIterable(HANDOFF_CHUNKS)
                .map(chunk -> response(List.of(TextBlock.builder().text(chunk).build()), "stop"));
        });
    }

    private static Model scriptedModel(String name,
                                       BiFunction<List<Msg>, List<ToolSchema>, Flux<ChatResponse>> script) {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return script.apply(messages, tools);
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
