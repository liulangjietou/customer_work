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
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
