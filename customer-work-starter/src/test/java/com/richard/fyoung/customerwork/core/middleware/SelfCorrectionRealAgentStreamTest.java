package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
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
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.sensitiveword.InMemorySensitiveWordStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordCategory;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
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
 * 答复安全闸门的 Jev 补拦：澄清落在哪、协议守不守得住、落库留不留得全——用真实 {@link ReActAgent} 与真实消费方验证。
 *
 * <p><b>为什么要真实跑一遍</b>：{@link SelfCorrectionJevTest} 手搓事件序列、只把增量拼起来比文本，
 * 于是澄清排在答复块结束之后也一直是绿的。而 Jev 判的是最终结果，框架发出最终结果时答复块<b>已经结束</b>；
 * AG-UI 适配器按回复标识拼消息、结束之后照发内容而不报错；外层敏感词过滤按块缓冲、只在块结束时放行尾巴。
 * 位置对不对只有真实消费方说了算，事件长什么样只有真实框架说了算。</p>
 *
 * <p>同一形状见 {@link LoopGuardRealAgentStreamTest}：往答复末尾追加说明，补在块结束之后就是这组问题。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SelfCorrectionRealAgentStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String SESSION = "u1:conv-self-correction";
    private static final String QUESTION = "我的退款到了吗";
    /** 没命中任何关键词、语义上却在断言钱已到账——只有 Jev 拦得住。分片吐出，真实模型就是这样流式的。 */
    private static final List<String> ANSWER_CHUNKS = List.of("您好，", "款项已原路返回", "您的支付账户，请留意查收。");
    private static final String ANSWER = String.join("", ANSWER_CHUNKS);
    private static final String CLARIFICATION =
        new CustomerWorkProperties().getHooks().getSelfCorrection().getClarification();
    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();
    /** 超过 Jev 的拦截阈值（默认 0.8）。 */
    private static final double CLAIM_PROBABILITY = 0.95;
    private static final int MAX_ITERS = 2;
    private static final String THINKING_ALOUD = "我先帮您查一下订单。";
    private static final List<String> SUMMARY_CHUNKS = List.of("抱歉让您久等了，", "款项已原路返回", "您的支付账户。");
    private static final String SUMMARY = String.join("", SUMMARY_CHUNKS);

    /** 离线工具：不是能为资金结论提供依据的查询工具，调了它说「钱已到账」照样该拦。 */
    public static class OrderTools {
        @Tool(description = "查询订单状态。用户问订单进度时调用。")
        public Mono<String> queryOrder(@ToolParam(name = "orderId", description = "订单号") String orderId) {
            return Mono.just("订单 " + orderId + " 的状态暂时无法确认");
        }
    }

    /**
     * 框架事实探针：本类其余用例与 {@code SelfCorrectionMiddleware} 扣住答复块结束的做法都建立在它之上。
     *
     * <p>红了说明框架在答复块结束与最终结果之间插进了别的事件。先别改断言：中间件扣住块结束时只放行
     * 模型调用结束这一种尾随事件，遇到别的就会提前放掉，Jev 拦截时的澄清随之退到单独成块——
     * 协议仍然合法，但 AG-UI 落库只剩澄清、丢了答复正文。回头核对中间件的尾随事件判定再动这里。</p>
     */
    @Test
    @DisplayName("框架事实：答复块在最终结果之前结束，其间只有模型调用结束事件")
    void frameworkEndsAnswerBlockBeforeResult() {
        List<AgentEvent> events = agent(answeringModel()).streamEvents(List.of(userMsg()), ctx())
            .collectList().block(TIMEOUT);

        int result = indexOf(events, AgentResultEvent.class);
        int blockEnd = lastIndexBefore(events, TextBlockEndEvent.class, result);
        assertTrue(blockEnd >= 0, "答复块应在最终结果之前结束：" + types(events));
        List<AgentEvent> between = events.subList(blockEnd + 1, result);
        assertTrue(between.stream().allMatch(ModelCallEndEvent.class::isInstance),
            "答复块结束与最终结果之间只应有模型调用结束事件：" + types(between));
    }

    /** 用户端主链路：WS / SSE / 同步接口都经 {@link ChatTurnService#stream} 走到 {@code chatStream}。 */
    @Test
    @DisplayName("用户端流式对话：澄清紧跟答复、恰好一次，落库与屏幕一致，并转人工")
    void streamingUserSeesClarificationOnce() {
        HandoffService handoff = mock(HandoffService.class);
        JevTestSupport.StubClient jev = JevTestSupport.noul(CLAIM_PROBABILITY);

        List<ChatTurnEvent> out = turns(agent(answeringModel(), selfCorrection(jev, handoff, props())))
            .stream(SESSION, QUESTION, null).collectList().block(TIMEOUT);

        String onScreen = screen(out);
        assertEquals(ANSWER + CLARIFICATION, onScreen, "澄清应紧跟答复且只出现一次");
        assertEquals(onScreen, completion(out).message().content(), "落库的历史消息应与用户屏幕一致");
        assertEquals(List.of(ANSWER), jev.states(), "送去判定的应当是用户看到的完整答复");
        verify(handoff, times(1)).create(eq(SESSION), contains("未经核实"));
    }

    /**
     * 外层敏感词过滤按块缓冲：可能扩展成敏感词的尾巴要等块结束才放行。
     *
     * <p>澄清若补在块结束之后，它的尾巴就再也等不到放行。这里让澄清以「转接人工」收尾、
     * 词库里有「人工智障」，「人工」两个字正是那段会被压住的歧义前缀。</p>
     */
    @Test
    @DisplayName("外层敏感词过滤压住的澄清尾巴随答复块结束一起放出")
    void outerSensitiveWordFilterReleasesClarificationTail() {
        CustomerWorkProperties props = props();
        String clarification = "\n\n【系统提示】以上关于到账的说明未经系统核实，已为您转接人工";
        props.getHooks().getSelfCorrection().setClarification(clarification);
        ReActAgent agent = agent(answeringModel(),
            selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY), mock(HandoffService.class), props),
            sensitiveWords("人工智障"));

        List<ChatTurnEvent> out = turns(agent).stream(SESSION, QUESTION, null).collectList().block(TIMEOUT);

        assertEquals(ANSWER + clarification, screen(out), "澄清的最后几个字不能被外层过滤压住不放");
    }

    /**
     * AG-UI（customer-channel 与 starter 的 {@code AguiService} 共用框架适配器）：只渲染文本消息事件，
     * 按回复标识拼消息；{@code AguiService} 落库只取最后一条消息——澄清必须落在答复那条消息里、赶在它结束之前。
     */
    @Test
    @DisplayName("AG-UI：澄清落在答复那条消息里，协议合法，落库的历史同样带上")
    void aguiSeesClarificationInsideAnswerMessage() {
        List<AguiEvent> events = new AguiAgentAdapter(
                agent(answeringModel(), selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY),
                    mock(HandoffService.class), props())),
                AguiAdapterConfig.builder().build())
            .run(aguiInput()).collectList().block(TIMEOUT);

        assertWellFormedTextMessages(events);
        List<AguiEvent.TextMessageContent> contents = events.stream()
            .filter(AguiEvent.TextMessageContent.class::isInstance)
            .map(AguiEvent.TextMessageContent.class::cast)
            .collect(Collectors.toList());
        String answerMessage = contents.stream()
            .filter(c -> c.delta().startsWith(ANSWER_CHUNKS.get(0))).findFirst().orElseThrow().messageId();
        String clarificationMessage = contents.stream()
            .filter(c -> c.delta().contains(CLARIFICATION.trim())).findFirst().orElseThrow().messageId();
        assertEquals(answerMessage, clarificationMessage, "澄清应落在答复那条消息里，而不是另起一条");

        InMemoryChatMessageStore store = new InMemoryChatMessageStore();
        ReActAgent agent = agent(answeringModel(),
            selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY), mock(HandoffService.class), props()));
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        new AguiService(factory, new CustomerWorkProperties(), new ChatTurnFinalizer(new ChatLogService(store)))
            .run(SESSION, QUESTION).blockLast(TIMEOUT);
        List<ChatMessage> saved = store.findBySession(SESSION, null, 10);
        assertEquals(1, saved.size(), "应落一条助手答复：" + saved);
        assertEquals(ANSWER + CLARIFICATION, saved.get(0).content(),
            "AG-UI 落库取最后一条消息：答复正文与澄清都要在，留痕里得看得出用户当时读到了什么");
    }

    /** 只读最终结果的消费方：IM 渠道、评测、多专家协作的专家调用。 */
    @Test
    @DisplayName("非流式 call()：最终结果带且只带一次澄清")
    void callPathSeesClarificationOnce() {
        Msg reply = agent(answeringModel(), selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY),
                mock(HandoffService.class), props()))
            .call(List.of(userMsg()), ctx()).block(TIMEOUT);

        assertEquals(ANSWER + CLARIFICATION, reply.getTextContent());
    }

    /**
     * 与循环守卫叠加：轮次用尽后框架收尾的那段话被 Jev 判为断言到账。
     *
     * <p>两个中间件往同一个收尾块里各补一段——澄清在内层先补、去向说明在外层后补，
     * 流式与最终结果顺序一致；框架标的 {@code MAX_ITERATIONS} 必须原样交给外层终止采集，
     * H5 据此显示「答复尚未完成」。</p>
     */
    @Test
    @DisplayName("轮次用尽的收尾被拦：澄清与去向说明各一次，结束原因如实上报")
    void blockedExhaustionSummaryKeepsFinishReason() {
        HandoffService handoff = mock(HandoffService.class);
        ReActAgent agent = agent(exhaustingModel(),
            selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY), handoff, props()),
            loopGuard(handoff), new ChatTerminalCaptureMiddleware());

        List<ChatTurnEvent> out = turns(agent).stream(SESSION, QUESTION, null).collectList().block(TIMEOUT);

        String onScreen = screen(out);
        assertTrue(onScreen.endsWith(SUMMARY + CLARIFICATION + NOTICE),
            "收尾之后先是澄清、再是去向说明：" + onScreen);
        assertEquals(onScreen, completion(out).message().content(), "落库的历史消息应与用户屏幕一致");
        assertEquals(GenerateReason.MAX_ITERATIONS.name(), completion(out).terminal().finishReason(),
            "改写最终结果不能把轮次用尽抹成正常结束，前端靠它显示「答复尚未完成」");

        List<AguiEvent> aguiEvents = new AguiAgentAdapter(agent(exhaustingModel(),
                selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY), mock(HandoffService.class), props()),
                loopGuard(mock(HandoffService.class))), AguiAdapterConfig.builder().build())
            .run(aguiInput()).collectList().block(TIMEOUT);
        assertWellFormedTextMessages(aguiEvents);

        Msg reply = agent(exhaustingModel(),
            selfCorrection(JevTestSupport.noul(CLAIM_PROBABILITY), mock(HandoffService.class), props()),
            loopGuard(mock(HandoffService.class))).call(List.of(userMsg()), ctx()).block(TIMEOUT);
        assertEquals(SUMMARY + CLARIFICATION + NOTICE, reply.getTextContent());
        assertEquals(GenerateReason.MAX_ITERATIONS, reply.getGenerateReason());
    }

    // ---------- 辅助 ----------

    /** 直接作答：一次模型调用、不调工具，答复分片流式吐出。 */
    private static Model answeringModel() {
        return new ScriptedModel() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.fromIterable(ANSWER_CHUNKS).map(chunk -> response(text(chunk), "stop"));
            }
        };
    }

    /**
     * 带工具清单的推理调用一律「先说一句再调工具」，直到轮次用尽；框架的收尾调用不带工具清单，
     * 据此分片吐出一段断言到账的收尾。
     */
    private static Model exhaustingModel() {
        AtomicInteger calls = new AtomicInteger();
        return new ScriptedModel() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (tools == null || tools.isEmpty()) {
                    return Flux.fromIterable(SUMMARY_CHUNKS).map(chunk -> response(text(chunk), "stop"));
                }
                int n = calls.incrementAndGet();
                return Flux.just(response(List.of(TextBlock.builder().text(THINKING_ALOUD).build(),
                    new ToolUseBlock("call-" + n, "queryOrder", Map.of("orderId", "SO-" + n))), "tool_calls"));
            }
        };
    }

    private abstract static class ScriptedModel implements Model {
        @Override
        public String getModelName() {
            return "stub-scripted-model";
        }
    }

    private static List<ContentBlock> text(String chunk) {
        return List.of(TextBlock.builder().text(chunk).build());
    }

    private static ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder()
            .id(UUID.randomUUID().toString())
            .content(content)
            .usage(new ChatUsage(1, 1, 0.0))
            .finishReason(finishReason)
            .build();
    }

    private ReActAgent agent(Model model, MiddlewareBase... middlewares) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderTools());
        ReActAgent.Builder builder = ReActAgent.builder()
            .name("self-correction-probe")
            .sysPrompt("你是电商客服助手，查订单时调用工具。")
            .model(model)
            .toolkit(toolkit)
            .maxIters(MAX_ITERS);
        for (MiddlewareBase middleware : middlewares) {
            builder.middleware(middleware);
        }
        return builder.build();
    }

    /** 与 C 端装配一致：真拦截、不发决策事件。 */
    private SelfCorrectionMiddleware selfCorrection(JevTestSupport.StubClient jev, HandoffService handoff,
                                                    CustomerWorkProperties props) {
        return new SelfCorrectionMiddleware(props, provider(handoff), provider((AuditSink) null),
            provider((MeterRegistry) new SimpleMeterRegistry()), () -> JevTestSupport.service(jev), JevRunMode.LIVE);
    }

    private LoopGuardMiddleware loopGuard(HandoffService handoff) {
        return new LoopGuardMiddleware(new CustomerWorkProperties(), provider(handoff), provider((AuditSink) null),
            provider((MeterRegistry) new SimpleMeterRegistry()));
    }

    private SensitiveWordMiddleware sensitiveWords(String maskedWord) {
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        store.save(SensitiveWord.of(maskedWord, SensitiveWordCategory.CUSTOM, SensitiveWordAction.MASK));
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getSensitiveWord().setEnabled(true);
        return new SensitiveWordMiddleware(props, new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK),
            new LoggingAuditSink(), provider((MeterRegistry) new SimpleMeterRegistry()),
            provider((SensitiveWordHitSink) null));
    }

    private static CustomerWorkProperties props() {
        return new CustomerWorkProperties();
    }

    /** 先建好 Agent 再交给 thenReturn：建 Agent 时会 stub 别的 mock，放进 thenReturn 参数里会触发 UnfinishedStubbing。 */
    private ChatTurnService turns(ReActAgent agent) {
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(anyString())).thenReturn(agent);
        when(factory.contextFor(anyString())).thenAnswer(inv ->
            RuntimeContext.builder().userId("tenant").sessionId(inv.getArgument(0)).build());
        return new ChatTurnService(new CustomerServiceService(factory, mock(SessionStateManager.class)),
            new ChatTurnFinalizer(new ChatLogService(new InMemoryChatMessageStore())));
    }

    private static String screen(List<ChatTurnEvent> out) {
        return out.stream()
            .filter(ChatTurnEvent.Delta.class::isInstance)
            .map(e -> ((ChatTurnEvent.Delta) e).content())
            .collect(Collectors.joining());
    }

    private static ChatTurnCompletion completion(List<ChatTurnEvent> out) {
        return ((ChatTurnEvent.Completed) out.get(out.size() - 1)).completion();
    }

    private Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).name("user")
            .content(TextBlock.builder().text(QUESTION).build()).build();
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

    private static int lastIndexBefore(List<AgentEvent> events, Class<? extends AgentEvent> type, int before) {
        for (int i = before - 1; i >= 0; i--) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> types(List<AgentEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
