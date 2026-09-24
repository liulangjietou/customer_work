package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 答复安全闸门的 Jev 语义补拦：关键词漏掉的同义表述由 Jev 补上，只收紧不放宽。
 */
class SelfCorrectionJevTest {

    /** 没命中任何默认关键词、但语义上就是在断言钱已经退了。 */
    private static final String PARAPHRASE = "款项已原路返回您的支付账户，请留意查收";
    private static final String CLARIFICATION_MARK = "未经系统核实";
    private static final String REPLY = "r1";
    /** 框架的文本块标识恒为 "text"（见真实 Agent 探针）。 */
    private static final String BLOCK = "text";

    private HandoffService handoff;
    private CustomerWorkProperties props;

    @BeforeEach
    void setUp() {
        handoff = mock(HandoffService.class);
        props = new CustomerWorkProperties();
        props.getHooks().getSelfCorrection().setEnabled(true);
    }

    @Test
    @DisplayName("前提：这句同义表述确实不在关键词覆盖范围内")
    void paraphraseEscapesKeywords() {
        SelfCorrectionMiddleware keywordOnly = new SelfCorrectionMiddleware(props,
            JevTestSupport.provider(handoff), JevTestSupport.provider((AuditSink) null), JevTestSupport.provider(null));

        assertFalse(keywordOnly.promisesPayment(PARAPHRASE), "若关键词已能拦下，本组用例就测不到 Jev 的作用");
    }

    @Test
    @DisplayName("非流式：Jev 判定断言到账，改写最终结果并转人工")
    void blocksParaphraseOnFinalResult() {
        List<AgentEvent> out = middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.just(new AgentResultEvent(msg(PARAPHRASE)))).collectList().block();

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        assertTrue(result.getResult().getTextContent().contains(CLARIFICATION_MARK));
        verify(handoff).create(anyString(), anyString());
    }

    /**
     * 流式下正文早已逐片推给用户，澄清必须也以增量的形式追加——只改最终结果等于改了个没人看的事件。
     * 判定的文本必须是用户实际看到的全部正文。
     */
    @Test
    @DisplayName("流式：澄清作为增量追加在末尾，判定依据是用户看到的完整正文")
    void appendsClarificationDeltaOnStream() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.95);

        List<AgentEvent> out = runStream(middleware(client, JevRunMode.LIVE), List.of(),
            List.of("款项已原路", "返回您的支付账户，", "请留意查收"));

        String emitted = deltaText(out);
        assertTrue(emitted.startsWith(PARAPHRASE), "正文应当原样送达（流式下本就收不回）：" + emitted);
        assertTrue(emitted.substring(PARAPHRASE.length()).contains(CLARIFICATION_MARK),
            "正文之后必须紧跟否定澄清：" + emitted);
        assertEquals(List.of(PARAPHRASE), client.states(), "送去判定的应当是拼好的完整正文");
    }

    /**
     * 澄清补在哪，决定了 AG-UI 协议守不守得住、外层按块缓冲的过滤放不放得出来。
     *
     * <p>{@link #appendsClarificationDeltaOnStream()} 只把增量拼起来比文本，澄清排在块结束之后也照样是绿的；
     * 而真实框架的答复块在最终结果到达之前就已结束（{@code SelfCorrectionRealAgentStreamTest} 的框架事实探针），
     * 块结束之后再往同一块追加，AG-UI 会收到已结束消息的内容。</p>
     */
    @Test
    @DisplayName("流式拦截：澄清补进答复块、赶在块结束之前，其余事件原序放出")
    void clarificationLandsInsideAnswerBlock() {
        List<AgentEvent> out = middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.fromIterable(
                finalAnswer(List.of("款项已原路", "返回您的支付账户，", "请留意查收"), msg(PARAPHRASE))))
            .collectList().block();

        int clarification = indexOfDelta(out, CLARIFICATION_MARK);
        assertTrue(clarification >= 0, "应当补出澄清：" + types(out));
        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) out.get(clarification);
        assertEquals(REPLY, delta.getReplyId(), "澄清应落在答复所在的那一块");
        assertEquals(BLOCK, delta.getBlockId());
        assertEquals(List.of(TextBlockDeltaEvent.class, TextBlockEndEvent.class, ModelCallEndEvent.class,
                AgentResultEvent.class), classesFrom(out, clarification),
            "澄清之后依次是答复块结束、模型调用结束、改写后的最终结果：" + types(out));
        assertEquals(1, out.stream().filter(TextBlockEndEvent.class::isInstance).count(), "块结束只能有一次");
    }

    /** 扣住块结束只为等判定：放行时事件序列必须与框架发出的一模一样，中间轮次的块结束也不被拖住。 */
    @Test
    @DisplayName("Jev 放行：整轮事件原样原序放出")
    void passedVerdictKeepsFrameworkOrder() {
        List<AgentEvent> upstream = new ArrayList<>();
        upstream.add(new TextBlockStartEvent("r0", BLOCK));
        upstream.add(new TextBlockDeltaEvent("r0", BLOCK, "我先帮您查一下。"));
        upstream.add(new TextBlockEndEvent("r0", BLOCK));
        upstream.add(new ToolCallStartEvent("r0", "t-1", "queryOrder"));
        upstream.add(new ModelCallEndEvent("r0", null));
        upstream.addAll(finalAnswer(List.of(PARAPHRASE), msg(PARAPHRASE)));

        List<AgentEvent> out = middleware(JevTestSupport.noul(0.3), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.fromIterable(upstream)).collectList().block();

        assertEquals(describe(upstream), describe(out), "放行时不应改动、增删或调换任何事件");
    }

    /**
     * 改写最终结果只能换内容。
     *
     * <p>重建消息会抹掉框架标的结束原因：外层终止采集把轮次用尽记成正常结束，H5 把「答复尚未完成」显示成
     * 「答复已生成」；消息 id 与用量也一并丢失。</p>
     */
    @Test
    @DisplayName("拦截改写保留结果消息的身份、结束原因与用量")
    void rewriteKeepsResultIdentityAndFinishReason() {
        ChatUsage usage = new ChatUsage(11, 7, 0.5);
        Msg exhausted = Msg.builder().id("reply-msg-1").role(MsgRole.ASSISTANT).name("assistant")
            .content(TextBlock.builder().text(PARAPHRASE).build())
            .generateReason(GenerateReason.MAX_ITERATIONS)
            .usage(usage)
            .build();

        List<AgentEvent> out = middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.fromIterable(finalAnswer(List.of(PARAPHRASE), exhausted)))
            .collectList().block();

        Msg rewritten = ((AgentResultEvent) out.get(out.size() - 1)).getResult();
        assertTrue(rewritten.getTextContent().startsWith(PARAPHRASE)
            && rewritten.getTextContent().contains(CLARIFICATION_MARK), rewritten.getTextContent());
        assertEquals(GenerateReason.MAX_ITERATIONS, rewritten.getGenerateReason(), "结束原因不能被改写成正常结束");
        assertEquals("reply-msg-1", rewritten.getId());
        assertEquals(usage, rewritten.getChatUsage());
    }

    /**
     * 最后一次模型调用在正文之后还发起了工具调用（如等待审批）：答复块早在工具调用开始时就放出去了，
     * 判定出来时已无块可补——澄清单独成一个完整的块，协议合法、流式用户看得到。
     */
    @Test
    @DisplayName("答复块已放出时：澄清单独成一个完整的块")
    void clarificationBlockWhenAnswerBlockAlreadyReleased() {
        ToolUseBlock refund = new ToolUseBlock("call-1", "submitRefund", Map.of("orderId", "SO-1"));
        Msg asking = Msg.builder().role(MsgRole.ASSISTANT)
            .content(List.of(TextBlock.builder().text(PARAPHRASE).build(), refund))
            .generateReason(GenerateReason.PERMISSION_ASKING)
            .build();
        List<AgentEvent> upstream = new ArrayList<>();
        upstream.add(new TextBlockStartEvent(REPLY, BLOCK));
        upstream.add(new TextBlockDeltaEvent(REPLY, BLOCK, PARAPHRASE));
        upstream.add(new TextBlockEndEvent(REPLY, BLOCK));
        upstream.add(new ToolCallStartEvent(REPLY, "call-1", "submitRefund"));
        upstream.add(new ModelCallEndEvent(REPLY, null));
        upstream.add(new AgentResultEvent(asking));

        List<AgentEvent> out = middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.fromIterable(upstream)).collectList().block();

        int clarification = indexOfDelta(out, CLARIFICATION_MARK);
        TextBlockDeltaEvent delta = (TextBlockDeltaEvent) out.get(clarification);
        assertFalse(REPLY.equals(delta.getReplyId()), "答复块已经结束，不能再往里追加：" + types(out));
        assertEquals(List.of(TextBlockStartEvent.class, TextBlockDeltaEvent.class, TextBlockEndEvent.class,
            AgentResultEvent.class), classesFrom(out, clarification - 1), "澄清应自成开始/增量/结束齐全的一块");
        assertEquals(delta.getReplyId(), ((TextBlockStartEvent) out.get(clarification - 1)).getReplyId());
        assertEquals(delta.getReplyId(), ((TextBlockEndEvent) out.get(clarification + 1)).getReplyId());
        assertEquals(GenerateReason.PERMISSION_ASKING,
            ((AgentResultEvent) out.get(out.size() - 1)).getResult().getGenerateReason());
    }

    /** 只延后、不丢弃：没等到最终结果流就结束或出错，扣住的块结束照样放出。 */
    @Test
    @DisplayName("流在最终结果之前结束或出错：扣住的块结束照样放出")
    void heldBlockEndIsNeverLost() {
        List<AgentEvent> answer = List.of(new TextBlockStartEvent(REPLY, BLOCK),
            new TextBlockDeltaEvent(REPLY, BLOCK, PARAPHRASE), new TextBlockEndEvent(REPLY, BLOCK),
            new ModelCallEndEvent(REPLY, null));

        List<AgentEvent> completed = middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null,
            JevTestSupport.ctx(), input(), in -> Flux.fromIterable(answer)).collectList().block();
        List<AgentEvent> failed = new ArrayList<>();
        middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE).onAgent(null, JevTestSupport.ctx(), input(),
                in -> Flux.fromIterable(answer).concatWith(Flux.error(new IllegalStateException("model down"))))
            .doOnNext(failed::add)
            .onErrorResume(e -> Flux.empty())
            .blockLast();

        assertEquals(describe(answer), describe(completed));
        assertEquals(describe(answer), describe(failed));
    }

    @Test
    @DisplayName("关键词已经拦下：不再问 Jev")
    void keywordHitSkipsJev() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.0);

        runStream(middleware(client, JevRunMode.LIVE), List.of(), List.of("已为您", "退款"));

        assertEquals(0, client.calls());
    }

    /**
     * 「只收紧不放宽」最直接的一条：关键词已经命中的，Jev 说什么都不能把它放出去。
     *
     * <p>必须单独测非流式路径：流式下关键词在逐片匹配时就已拦下，根本走不到最终结果的判定，
     * 只测流式照不出「非流式下关键词命中后又去问 Jev、Jev 说没事就放行」这种倒退——变异测试抓到过这一次。</p>
     */
    @Test
    @DisplayName("非流式下关键词已命中：Jev 判为否也照样拦，且根本不问 Jev")
    void keywordHitOnFinalResultIsNeverRelaxedByJev() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.0);

        List<AgentEvent> out = middleware(client, JevRunMode.LIVE).onAgent(null, JevTestSupport.ctx(), input(),
            in -> Flux.just(new AgentResultEvent(msg("已为您退款，三日内到账")))).collectList().block();

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        assertTrue(result.getResult().getTextContent().contains(CLARIFICATION_MARK), "Jev 把关键词拦下的内容放出去了");
        assertEquals(0, client.calls());
    }

    @Test
    @DisplayName("本轮查过真实状态：是正常转述，不问 Jev")
    void evidenceSkipsJev() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.99);

        List<AgentEvent> out = runStream(middleware(client, JevRunMode.LIVE), List.of("queryRefundProgress"),
            List.of(PARAPHRASE));

        assertEquals(0, client.calls());
        assertFalse(deltaText(out).contains(CLARIFICATION_MARK));
    }

    @Test
    @DisplayName("Jev 判为未断言到账、或 Jev 不可用：原样放行（回到纯关键词行为）")
    void passesWhenJevSaysNoOrIsDown() {
        for (JevTestSupport.StubClient client : List.of(JevTestSupport.noul(0.3), JevTestSupport.unavailable(),
            JevTestSupport.failing())) {
            List<AgentEvent> out = runStream(middleware(client, JevRunMode.LIVE), List.of(), List.of(PARAPHRASE));

            assertEquals(PARAPHRASE, deltaText(out));
        }
        verify(handoff, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("后台真执行（LIVE_TRACED）：拦截的同时展示决策；C 端（LIVE）不展示")
    void tracedModeEmitsDecision() {
        List<AgentEvent> traced = runStream(middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE_TRACED),
            List.of(), List.of(PARAPHRASE));
        List<AgentEvent> live = runStream(middleware(JevTestSupport.noul(0.95), JevRunMode.LIVE),
            List.of(), List.of(PARAPHRASE));

        Map<String, Object> decision = ((CustomEvent) traced.stream().filter(JevDecisionEvent::isDecision)
            .findFirst().orElseThrow()).getValue();
        assertEquals(JevDecisionEvent.POINT_PAYMENT_CLAIM, decision.get(JevDecisionEvent.KEY_POINT));
        assertEquals(true, decision.get(JevDecisionEvent.KEY_EXECUTED));
        assertEquals("live_traced", decision.get(JevDecisionEvent.KEY_RUN_MODE));
        assertFalse(live.stream().anyMatch(JevDecisionEvent::isDecision), "C 端不得出现决策事件");
    }

    @Test
    @DisplayName("子 Agent 的结果不判定：只判主 Agent 的答复，一轮只调一次")
    void subagentResultNotJudged() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.95);

        middleware(client, JevRunMode.LIVE).onAgent(null, JevTestSupport.ctx(), input(),
            in -> Flux.just((AgentEvent) new AgentResultEvent(msg(PARAPHRASE)).withSource("main/helper")))
            .collectList().block();

        assertEquals(0, client.calls());
    }

    // ---------- 辅助 ----------

    private SelfCorrectionMiddleware middleware(JevTestSupport.StubClient client, JevRunMode mode) {
        return new SelfCorrectionMiddleware(props, JevTestSupport.provider(handoff),
            JevTestSupport.provider((AuditSink) null), JevTestSupport.provider(null),
            () -> JevTestSupport.service(client), mode);
    }

    private List<AgentEvent> runStream(SelfCorrectionMiddleware mw, List<String> tools, List<String> deltas) {
        return mw.onAgent(null, JevTestSupport.ctx(), input(), in -> {
            List<AgentEvent> events = new ArrayList<>();
            tools.forEach(name -> events.add(new ToolCallStartEvent("r", "t-" + name, name)));
            deltas.forEach(d -> events.add(new TextBlockDeltaEvent("r", "b", d)));
            events.add(new TextBlockEndEvent("r", "b"));
            events.add(new AgentResultEvent(msg(String.join("", deltas))));
            return Flux.fromIterable(events);
        }).collectList().block();
    }

    /** 真实框架的最终答复形态：块开始 → 增量 → 块结束 → 模型调用结束 → 最终结果。 */
    private static List<AgentEvent> finalAnswer(List<String> deltas, Msg result) {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new TextBlockStartEvent(REPLY, BLOCK));
        deltas.forEach(d -> events.add(new TextBlockDeltaEvent(REPLY, BLOCK, d)));
        events.add(new TextBlockEndEvent(REPLY, BLOCK));
        events.add(new ModelCallEndEvent(REPLY, null));
        events.add(new AgentResultEvent(result));
        return events;
    }

    private static int indexOfDelta(List<AgentEvent> events, String part) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof TextBlockDeltaEvent delta && delta.getDelta().contains(part)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Class<?>> classesFrom(List<AgentEvent> events, int from) {
        List<Class<?>> classes = new ArrayList<>();
        for (AgentEvent event : events.subList(from, events.size())) {
            classes.add(event.getClass());
        }
        return classes;
    }

    private static List<String> types(List<AgentEvent> events) {
        List<String> names = new ArrayList<>();
        events.forEach(e -> names.add(e.getClass().getSimpleName()));
        return names;
    }

    /**
     * 事件序列的可比形态：相邻的同块增量合并成一段文本（匹配器会按关键词长度重新切片，切法不是契约），
     * 其余事件保留类型与所属回复。
     */
    private static List<String> describe(List<AgentEvent> events) {
        List<String> out = new ArrayList<>();
        String textKey = null;
        StringBuilder text = new StringBuilder();
        for (AgentEvent event : events) {
            if (event instanceof TextBlockDeltaEvent delta) {
                String key = delta.getReplyId() + "/" + delta.getBlockId();
                if (!key.equals(textKey)) {
                    flushText(out, textKey, text);
                    textKey = key;
                }
                text.append(delta.getDelta());
                continue;
            }
            flushText(out, textKey, text);
            textKey = null;
            out.add(event.getClass().getSimpleName() + ":" + ownerOf(event));
        }
        flushText(out, textKey, text);
        return out;
    }

    private static void flushText(List<String> out, String key, StringBuilder text) {
        if (key != null) {
            out.add("Text:" + key + ":" + text);
        }
        text.setLength(0);
    }

    private static String ownerOf(AgentEvent event) {
        if (event instanceof TextBlockStartEvent start) {
            return start.getReplyId();
        }
        if (event instanceof TextBlockEndEvent end) {
            return end.getReplyId();
        }
        if (event instanceof AgentResultEvent result) {
            return result.getResult().getTextContent();
        }
        return "";
    }

    private static String deltaText(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent delta) {
                sb.append(delta.getDelta());
            }
        }
        return sb.toString();
    }

    private static AgentInput input() {
        return new AgentInput(List.of(Msg.builder().role(MsgRole.USER)
            .content(TextBlock.builder().text("我的退款到了吗").build()).build()));
    }

    private static Msg msg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text(text).build()).build();
    }
}
