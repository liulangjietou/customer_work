package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.AguiService;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.service.ChatTurnCompletion;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnFinalizer;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.ChatMessage;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.richard.fyoung.customerwork.core.middleware.AguiTextMessageAssertions.assertWellFormedTextMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代耗尽时的去向说明，用户到底看不看得到——用真实 {@link ReActAgent} 与真实消费方验证。
 *
 * <p><b>为什么要真实跑一遍</b>：{@link LoopGuardMiddlewareTest} 手搓事件序列、只对
 * {@link AgentResultEvent} 下断言，于是「说明追加到了最终结果上」一直是绿的。而用户端
 * （WS / SSE，以及复用流式内核的同步接口）走 {@code CustomerServiceService#chatStream}：
 * 它只把 {@link TextBlockDeltaEvent} 推给前端，拿到过正文增量就不再看最终结果。
 * 框架的收尾回复本身就是逐片流式发出的——这是框架的事实，手搓的事件序列证明不了它，
 * 只有真实驱动一轮 ReAct 循环才算数。</p>
 *
 * <p>与 {@code SelfCorrectionMiddleware} 注释里记下的「只看最终结果在流式路径上是假性生效」同一形状。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class LoopGuardRealAgentStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_ITERS = 2;
    private static final String SESSION = "u1:conv-loop-guard";
    private static final String TOOL_NAME = "queryOrder";
    /** 每一轮推理都先说一句再调工具：用户在轮次用尽之前就已经收到过正文增量。 */
    private static final String THINKING_ALOUD = "我先帮您查一下订单。";
    /** 收尾回复分片流式吐出——真实模型的收尾也是流式的。 */
    private static final List<String> SUMMARY_CHUNKS = List.of("抱歉，", "我暂时没能", "处理好这个问题。");
    private static final String SUMMARY = String.join("", SUMMARY_CHUNKS);
    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();

    /** 离线工具：真实注册进 Toolkit，被 ReAct 循环真实调用。 */
    public static class OrderTools {
        @Tool(description = "查询订单状态。用户问订单进度时调用。")
        public Mono<String> queryOrder(@ToolParam(name = "orderId", description = "订单号") String orderId) {
            return Mono.just("订单 " + orderId + " 的状态暂时无法确认");
        }
    }

    /**
     * 框架事实探针：本类其余用例与 {@code LoopGuardMiddleware} 的补发位置都建立在它之上。
     *
     * <p>红了说明框架改变了轮次用尽时收尾回复的发出方式。先别改断言，回头核对
     * {@code LoopGuardMiddleware} 的补发位置：若收尾不再流式，它会退到最终结果之前补发，
     * 仍然只出现一次，但放的位置可能已经不是用户期望的那一处。</p>
     */
    @Test
    @DisplayName("框架事实：轮次用尽后的收尾回复以正文增量逐片发出，最终结果只是它的汇总")
    void frameworkStreamsExhaustionSummaryAsTextDeltas() {
        List<AgentEvent> events = agent().streamEvents(List.of(userMsg()), ctx())
            .collectList().block(TIMEOUT);

        int exceeded = indexOf(events, ExceedMaxItersEvent.class);
        int result = indexOf(events, AgentResultEvent.class);
        assertTrue(exceeded >= 0 && result > exceeded,
            "轮次用尽事件应先于最终结果到达：" + types(events));
        List<AgentEvent> summary = events.subList(exceeded + 1, result);
        assertEquals(SUMMARY, deltaText(summary),
            "收尾回复应以正文增量逐片发出：" + types(summary));
        assertTrue(summary.stream().anyMatch(TextBlockEndEvent.class::isInstance),
            "收尾文本块应在最终结果之前结束：" + types(summary));

        Msg finalMsg = ((AgentResultEvent) events.get(result)).getResult();
        assertEquals(SUMMARY, finalMsg.getTextContent(), "最终结果就是已经流式发出过的那段收尾");
        assertEquals(GenerateReason.MAX_ITERATIONS, finalMsg.getGenerateReason(),
            "框架在收尾消息上标了结束原因，外层的终止采集靠它告诉前端「答复尚未完成」");
    }

    /**
     * 用户端主链路：WS / SSE / 同步接口都经 {@link ChatTurnService#stream} 走到 {@code chatStream}。
     *
     * <p>三处都要对：屏幕上看到的、落库的历史消息、终止信封里的结束原因（前端按它显示答复状态）。</p>
     */
    @Test
    @DisplayName("用户端流式对话：轮次用尽时屏幕与落库都带且只带一次去向说明，结束原因如实上报")
    void streamingUserSeesNoticeExactlyOnce() {
        HandoffService handoffService = mock(HandoffService.class);
        // 先建好再交给 thenReturn：建 Agent 时会 stub 别的 mock，放进 thenReturn 参数里会触发 UnfinishedStubbing
        ReActAgent agent = agent(loopGuard(handoffService), new ChatTerminalCaptureMiddleware());
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        ChatTurnService turns = new ChatTurnService(
            new CustomerServiceService(factory, mock(SessionStateManager.class)),
            new ChatTurnFinalizer(new ChatLogService(new InMemoryChatMessageStore())));

        List<ChatTurnEvent> out = turns.stream(SESSION, "我的订单怎么还没到", null)
            .collectList().block(TIMEOUT);

        String onScreen = out.stream()
            .filter(ChatTurnEvent.Delta.class::isInstance)
            .map(e -> ((ChatTurnEvent.Delta) e).content())
            .collect(Collectors.joining());
        ChatTurnCompletion completion = ((ChatTurnEvent.Completed) out.get(out.size() - 1)).completion();
        assertTrue(onScreen.contains(SUMMARY), "框架生成的收尾应照常到达用户：" + onScreen);
        assertEquals(1, occurrences(onScreen, NOTICE), "用户屏幕上的去向说明必须恰好一次：" + onScreen);
        assertTrue(onScreen.endsWith(NOTICE), "去向说明应落在本轮回复的末尾：" + onScreen);
        assertEquals(onScreen, completion.message().content(), "落库的历史消息应与用户屏幕一致");
        assertEquals(GenerateReason.MAX_ITERATIONS.name(), completion.terminal().finishReason(),
            "终止信封的结束原因不能被改写成正常结束，前端靠它显示「答复尚未完成」");
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
    }

    /**
     * AG-UI（customer-channel 与 starter 的 {@code AguiService} 共用框架适配器）：只渲染文本消息事件，
     * 最终结果里的文本一个字都不展示；它按回复标识拼消息，已结束的消息不能再追加内容；
     * {@code AguiService} 落库又只取最后一条消息——说明必须落在收尾那条消息里、赶在它结束之前。
     */
    @Test
    @DisplayName("AG-UI：说明落在收尾那条消息里，协议合法，落库的历史同样带上")
    void aguiSeesNoticeInsideSummaryMessage() {
        List<AguiEvent> events = new AguiAgentAdapter(agent(loopGuard(mock(HandoffService.class))),
                AguiAdapterConfig.builder().build())
            .run(aguiInput()).collectList().block(TIMEOUT);

        assertWellFormedTextMessages(events);
        List<AguiEvent.TextMessageContent> contents = events.stream()
            .filter(AguiEvent.TextMessageContent.class::isInstance)
            .map(AguiEvent.TextMessageContent.class::cast)
            .collect(Collectors.toList());
        assertEquals(1, contents.stream().filter(c -> c.delta().contains(NOTICE.trim())).count(),
            "AG-UI 上的去向说明必须恰好一次：" + contents);
        String summaryMessage = contents.stream()
            .filter(c -> SUMMARY_CHUNKS.get(0).equals(c.delta())).findFirst().orElseThrow().messageId();
        String noticeMessage = contents.stream()
            .filter(c -> NOTICE.equals(c.delta())).findFirst().orElseThrow().messageId();
        assertEquals(summaryMessage, noticeMessage, "说明应落在收尾那条消息里，而不是另起一条");

        InMemoryChatMessageStore store = new InMemoryChatMessageStore();
        ReActAgent agent = agent(loopGuard(mock(HandoffService.class)));
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        new AguiService(factory, new CustomerWorkProperties(), new ChatTurnFinalizer(new ChatLogService(store)))
            .run(SESSION, "我的订单怎么还没到").blockLast(TIMEOUT);
        List<ChatMessage> saved = store.findBySession(SESSION, null, 10);
        assertEquals(1, saved.size(), "应落一条助手答复：" + saved);
        assertEquals(SUMMARY + NOTICE, saved.get(0).content(), "AG-UI 落库取最后一条消息，收尾正文与说明都要在");
    }

    /** 只读最终结果的消费方：IM 渠道、评测（多专家协作的专家是内部调用，由编排器收尾，见 MultiAgentTurnSettlementTest）。 */
    @Test
    @DisplayName("非流式 call()：最终结果同样恰好一次，且保留框架标的结束原因")
    void callPathSeesNoticeExactlyOnce() {
        Msg reply = agent(loopGuard(mock(HandoffService.class)))
            .call(List.of(userMsg()), ctx()).block(TIMEOUT);

        assertEquals(SUMMARY + NOTICE, reply.getTextContent());
        assertEquals(GenerateReason.MAX_ITERATIONS, reply.getGenerateReason(),
            "追加说明不应抹掉框架标在收尾消息上的结束原因");
    }

    // ---------- 辅助 ----------

    /**
     * 离线脚本模型：带工具清单的推理调用一律「先说一句再调工具」，永不收敛，直到轮次用尽；
     * 框架的收尾调用不带工具清单（{@code summaryModelCallStream} 传的是空列表），据此分片吐出收尾回复。
     */
    private static Model loopingModel() {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                if (tools == null || tools.isEmpty()) {
                    return Flux.fromIterable(SUMMARY_CHUNKS)
                        .map(chunk -> response(List.of(TextBlock.builder().text(chunk).build()), "stop"));
                }
                int n = calls.incrementAndGet();
                return Flux.just(response(List.of(
                    TextBlock.builder().text(THINKING_ALOUD).build(),
                    new ToolUseBlock("call-" + n, TOOL_NAME, Map.of("orderId", "SO-" + n))), "tool_calls"));
            }

            @Override
            public String getModelName() {
                return "stub-looping-model";
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

    private ReActAgent agent(MiddlewareBase... middlewares) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        ReActAgent.Builder builder = ReActAgent.builder()
            .name("loop-guard-probe")
            .sysPrompt("你是电商客服助手，查订单时调用工具。")
            .model(loopingModel())
            .toolkit(toolkit)
            .maxIters(MAX_ITERS);
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        return builder.build();
    }

    private LoopGuardMiddleware loopGuard(HandoffService handoffService) {
        return new LoopGuardMiddleware(new CustomerWorkProperties(),
            provider(handoffService), provider((AuditSink) null), provider((MeterRegistry) new SimpleMeterRegistry()));
    }

    private Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text("我的订单怎么还没到").build()).build();
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("tenant").sessionId(SESSION).build();
    }

    private RunAgentInput aguiInput() {
        return new RunAgentInput(SESSION, "run-" + UUID.randomUUID(),
            new AguiMessageConverter().toAguiMessageList(List.of(userMsg())),
            List.of(), List.of(), Map.of(), Map.of());
    }

    private static int indexOf(List<AgentEvent> events, Class<? extends AgentEvent> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String deltaText(List<AgentEvent> events) {
        return events.stream()
            .filter(TextBlockDeltaEvent.class::isInstance)
            .map(e -> ((TextBlockDeltaEvent) e).getDelta())
            .collect(Collectors.joining());
    }

    private static List<String> types(List<AgentEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).collect(Collectors.toList());
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int from = text.indexOf(part); from >= 0; from = text.indexOf(part, from + part.length())) {
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
