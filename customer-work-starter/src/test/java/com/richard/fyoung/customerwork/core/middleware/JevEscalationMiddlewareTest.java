package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevEscalationMiddlewareTest {

    private HandoffService handoff;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        handoff = mock(HandoffService.class);
        when(handoff.create(anyString(), anyString()))
            .thenReturn(new HandoffTicket("TK-9", "u1:conv-1", "r", System.currentTimeMillis()));
        ctx = JevTestSupport.ctx();
    }

    @Test
    @DisplayName("高置信要求人工：建工单转人工，并告诉模型已转接、不要再调转人工工具")
    void autoHandoffThenTellsModel() {
        JevEscalationMiddleware mw = live(JevTestSupport.furiousTurn());

        runTurn(mw, "我要投诉！马上给我转人工！");
        String hint = injectedHint(mw);

        verify(handoff, times(1)).create(eq("u1:conv-1"), anyString());
        assertTrue(hint.contains("已为其转接人工坐席") && hint.contains("TK-9"), hint);
    }

    /**
     * ReAct 循环里 onReasoning 每迭代一次都会进，自动转人工这个副作用必须只执行一次。
     */
    @Test
    @DisplayName("同一轮多次推理迭代：工单只建一次，提示每次都在")
    void handoffHappensOncePerTurn() {
        JevEscalationMiddleware mw = live(JevTestSupport.furiousTurn());

        runTurn(mw, "转人工！");
        String first = injectedHint(mw);
        String second = injectedHint(mw);

        verify(handoff, times(1)).create(anyString(), anyString());
        assertEquals(first, second);
    }

    @Test
    @DisplayName("明显不满：只提示模型考虑转人工，不自动转")
    void upsetOnlyHints() {
        JevEscalationMiddleware mw = live(JevTestSupport.upsetTurn());

        runTurn(mw, "怎么又是这样，我都问第三遍了");

        verify(handoff, never()).create(anyString(), anyString());
        assertTrue(injectedHint(mw).contains("请调用转人工工具"));
    }

    @Test
    @DisplayName("情绪平稳：模型输入原封不动")
    void calmLeavesInputUntouched() {
        JevEscalationMiddleware mw = live(JevTestSupport.calmTurn("order", 0.9));
        runTurn(mw, "帮我查下订单");

        ReasoningInput input = reasoningInput();
        assertSame(input, captureReasoning(mw, input), "不需要处置时不应复制或改写输入");
    }

    @Test
    @DisplayName("转人工失败：退回提示模型，对话照常")
    void handoffFailureFallsBackToHint() {
        when(handoff.create(anyString(), anyString())).thenThrow(new IllegalStateException("db down"));
        JevEscalationMiddleware mw = live(JevTestSupport.furiousTurn());

        runTurn(mw, "转人工！");

        assertTrue(injectedHint(mw).contains("请调用转人工工具"), "建单失败不能谎称已转接");
    }

    /**
     * {@code Mono.fromCallable} 返回 null 会变成空流。判定链路一旦为空，
     * 后面的 {@code flatMapMany} 永远不会调下游——这一轮对话直接没有回复，且不报任何错。
     */
    @Test
    @DisplayName("工单号为空：这一轮照样有回复")
    void nullTicketIdDoesNotSilenceTurn() {
        when(handoff.create(anyString(), anyString()))
            .thenReturn(new HandoffTicket(null, "u1:conv-1", "r", System.currentTimeMillis()));
        JevEscalationMiddleware mw = live(JevTestSupport.furiousTurn());
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        mw.onAgent(null, ctx, new AgentInput(List.of(user("转人工！"))), in -> {
            calls.incrementAndGet();
            return Flux.empty();
        }).blockLast();

        assertEquals(1, calls.get(), "判定链路为空时下游一次都没被调用：这一轮没有回复");
    }

    @Test
    @DisplayName("没有工单服务：退回提示模型")
    void noHandoffServiceFallsBackToHint() {
        JevEscalationMiddleware mw = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.furiousTurn())),
            JevTestSupport.provider(null));

        runTurn(mw, "转人工！");

        assertTrue(injectedHint(mw).contains("请调用转人工工具"));
    }

    /**
     * 后台影子只展示判定，<b>绝不改变模型的行为</b>：不建工单、不注入提示。
     * 否则运营在后台调试时看到的回复，就不是线上真实会给出的回复。
     */
    @Test
    @DisplayName("影子模式：发出决策事件，但不转人工、不改模型输入")
    void shadowOnlyReports() {
        JevEscalationMiddleware mw = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.furiousTurn())),
            JevTestSupport.provider(handoff), JevRunMode.SHADOW);

        List<AgentEvent> out = runTurn(mw, "转人工！");
        ReasoningInput input = reasoningInput();

        verify(handoff, never()).create(anyString(), anyString());
        assertSame(input, captureReasoning(mw, input), "影子模式改了模型输入");
        Map<String, Object> decision = onlyDecision(out);
        assertEquals(JevDecisionEvent.POINT_ESCALATION, decision.get(JevDecisionEvent.KEY_POINT));
        assertEquals("直接转人工坐席", decision.get(JevDecisionEvent.KEY_ACTION));
        assertEquals(false, decision.get(JevDecisionEvent.KEY_EXECUTED));
        assertEquals("jev-test", decision.get(JevDecisionEvent.KEY_MODEL));
        assertEquals("shadow", decision.get(JevDecisionEvent.KEY_RUN_MODE));
    }

    /**
     * 「明显不满」在 LIVE 下会注入提示，是影子模式真正可能误改模型输入的那一档。
     * 只测「要求人工」照不出来：影子下没建工单，那一档本就不产生提示。
     */
    @Test
    @DisplayName("影子模式遇到明显不满：同样不注入提示")
    void shadowNeverInjectsHint() {
        JevEscalationMiddleware mw = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.upsetTurn())),
            JevTestSupport.provider(handoff), JevRunMode.SHADOW);

        List<AgentEvent> out = runTurn(mw, "怎么又是这样");
        ReasoningInput input = reasoningInput();

        assertSame(input, captureReasoning(mw, input), "影子模式注入了提示，后台看到的回复将与线上不同");
        assertEquals("提示模型考虑转人工", onlyDecision(out).get(JevDecisionEvent.KEY_ACTION));
    }

    @Test
    @DisplayName("影子模式下 Jev 不可用：展示一个降级节点，而不是什么都不显示")
    void shadowReportsDegraded() {
        JevEscalationMiddleware mw = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.unavailable())),
            JevTestSupport.provider(handoff), JevRunMode.SHADOW);

        Map<String, Object> decision = onlyDecision(runTurn(mw, "你好"));

        assertEquals(true, decision.get(JevDecisionEvent.KEY_DEGRADED));
        assertEquals(JevDecisionEvent.POINT_TURN, decision.get(JevDecisionEvent.KEY_POINT));
    }

    /**
     * 知识库注入中间件追加的召回块也是 USER 角色。拿它去判情绪，判的是知识库文档的情绪。
     */
    @Test
    @DisplayName("判定依据是用户原话，跳过知识库注入的合成消息")
    void judgesUserTextNotSyntheticMessages() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("order", 0.9);
        JevEscalationMiddleware mw = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(client)), JevTestSupport.provider(handoff));
        Msg synthetic = Msg.builder().role(MsgRole.USER).name("system")
            .content(TextBlock.builder().text("【知识库】退货政策：……").build())
            .metadata(Map.of(Msg.METADATA_SYNTHETIC, true)).build();

        mw.onAgent(null, ctx, new AgentInput(List.of(user("我的订单到哪了"), synthetic)), in -> Flux.empty())
            .blockLast();

        assertEquals(List.of("我的订单到哪了"), client.states());
    }

    // ---------- 辅助 ----------

    private JevEscalationMiddleware live(JevTestSupport.StubClient client) {
        return new JevEscalationMiddleware(JevTestSupport.provider(JevTestSupport.service(client)),
            JevTestSupport.provider(handoff));
    }

    private List<AgentEvent> runTurn(JevEscalationMiddleware mw, String userText) {
        return mw.onAgent(null, ctx, new AgentInput(List.of(user(userText))), in -> Flux.empty())
            .collectList().block();
    }

    /** 跑一次推理，返回被注入的提示文本；没注入返回空串。 */
    private String injectedHint(JevEscalationMiddleware mw) {
        ReasoningInput original = reasoningInput();
        ReasoningInput seen = captureReasoning(mw, original);
        if (seen.messages().size() == original.messages().size()) {
            return "";
        }
        Msg last = seen.messages().get(seen.messages().size() - 1);
        assertTrue(last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(Msg.METADATA_SYNTHETIC)),
            "提示必须是带合成标记的瞬态消息，否则会被写进会话历史");
        return last.getTextContent();
    }

    private ReasoningInput captureReasoning(JevEscalationMiddleware mw, ReasoningInput input) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        mw.onReasoning(null, ctx, input, in -> {
            seen.set(in);
            return Flux.empty();
        }).blockLast();
        return seen.get();
    }

    private static ReasoningInput reasoningInput() {
        return new ReasoningInput(List.of(user("用户的话")), List.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyDecision(List<AgentEvent> out) {
        List<AgentEvent> decisions = out.stream().filter(JevDecisionEvent::isDecision).toList();
        assertEquals(1, decisions.size(), "每轮每个决策点只展示一次：" + decisions);
        return ((CustomEvent) decisions.get(0)).getValue();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }
}
