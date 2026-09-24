package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.ConversationTurn;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 循环守卫只处置「对用户说话的那次调用」自己的轮次。
 *
 * <p>两种不是本轮自己的情形：
 * <ul>
 *   <li><b>转发进来的子智能体事件</b>（{@code getSource() != null}）：Harness 子智能体同步执行时，
 *       它的细粒度事件只转发进父智能体的事件流。此前父智能体把子智能体的轮次用尽当成自己的，
 *       于是父智能体照常答完了也会被转人工、被追加「轮次上限」的说明；</li>
 *   <li><b>替本轮干活的内部调用</b>（上下文会话与本轮用户会话不一致）：多专家的专家 / 归纳器、
 *       Harness 异步子智能体。此前在它们的派生会话上转人工，建出一张没人能接到用户的工单。</li>
 * </ul>
 * 真实框架下的事件形状由 {@code LoopGuardSubagentForwardingTest} 与 {@code MultiAgentTurnSettlementTest} 钉住。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class LoopGuardDelegationTest {

    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();
    private static final String USER_SESSION = "u1:conv-1";
    private static final String CHILD = USER_SESSION + "/OrderExpert";
    private static final String PARENT_ANSWER = "您的订单已发货，预计明天送达。";

    private HandoffService handoffService;
    private AuditSink auditSink;
    private MeterRegistry registry;
    private LoopGuardMiddleware middleware;

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        registry = new SimpleMeterRegistry();
        middleware = new LoopGuardMiddleware(new CustomerWorkProperties(),
            provider(handoffService), provider(auditSink), provider(registry));
    }

    @Test
    @DisplayName("子智能体转发进来的轮次用尽不算父智能体的：父智能体照常答完，不转人工、不追加说明")
    void forwardedChildExhaustionDoesNotMarkParentTurn() {
        List<AgentEvent> events = new ArrayList<>();
        events.add(new ExceedMaxItersEvent("", 2, 2).withSource(CHILD));
        events.add(new TextBlockStartEvent("child-r", "text").withSource(CHILD));
        events.add(new TextBlockDeltaEvent("child-r", "text", "抱歉，没能查到。").withSource(CHILD));
        events.add(new TextBlockEndEvent("child-r", "text").withSource(CHILD));
        events.addAll(answerBlock("parent-r", PARENT_ANSWER));
        events.add(new AgentResultEvent(msg(PARENT_ANSWER)));

        List<AgentEvent> out = run(ctx(USER_SESSION, null), events);

        assertEquals(events, out, "父智能体这一轮应原样透传，不补任何增量");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("子智能体的轮次用尽在父智能体这一层恰好记一次，并以子智能体的名义记")
    void forwardedChildExhaustionIsRecordedOnceUnderTheChild() {
        run(ctx(USER_SESSION, null), List.of(
            new ExceedMaxItersEvent("", 2, 2).withSource(CHILD),
            new AgentResultEvent(msg(PARENT_ANSWER))));

        assertEquals(1.0, registry.counter("customerwork.agent.iters.exhausted").count());
        Map<String, Object> audit = auditOf("agent-iters-exhausted");
        assertEquals(CHILD, audit.get("agent"), "应记在子智能体名下：" + audit);
        assertEquals(true, audit.get("delegated"));
    }

    /**
     * 子智能体与父智能体的工具调用各记各的账：两边合起来凑够阈值不是任何一方在绕圈，
     * 而子智能体自己绕圈时告警要落在它名下。
     */
    @Test
    @DisplayName("重复调用告警按调用方分开计数：父子合计凑够阈值不告警，子智能体自己绕圈才告警且记在它名下")
    void repeatedCallsAreCountedPerCaller() {
        List<AgentEvent> mixed = new ArrayList<>();
        mixed.addAll(toolCall("p1", "queryOrder", "{\"orderId\":\"SO-1\"}", null));
        mixed.addAll(toolCall("p2", "queryOrder", "{\"orderId\":\"SO-1\"}", null));
        mixed.addAll(toolCall("c1", "queryOrder", "{\"orderId\":\"SO-1\"}", CHILD));
        run(ctx(USER_SESSION, null), mixed);
        verify(auditSink, never()).record(eq("agent-tool-call-repeated"), anyMap());

        List<AgentEvent> childLoop = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            childLoop.addAll(toolCall("c" + i, "queryOrder", "{\"orderId\":\"SO-1\"}", CHILD));
        }
        run(ctx(USER_SESSION, null), childLoop);
        assertEquals(CHILD, auditOf("agent-tool-call-repeated").get("agent"));
    }

    @Test
    @DisplayName("内部调用轮次用尽：不在派生会话上转人工、不追加说明，改为上报给本轮组织者")
    void delegatedInvocationEscalatesInsteadOfActing() {
        ConversationTurn turn = ConversationTurn.open(USER_SESSION);
        List<AgentEvent> events = exhaustedTurn();

        List<AgentEvent> out = run(ctx(USER_SESSION + "#mas-consult", turn), events);

        assertEquals(events, out, "内部调用的事件与收尾结果应原样透传——说明由组织者接在最终答复上");
        Msg result = ((AgentResultEvent) out.get(out.size() - 1)).getResult();
        assertEquals(GenerateReason.MAX_ITERATIONS, result.getGenerateReason());
        verify(handoffService, never()).create(anyString(), anyString());

        assertTrue(turn.hasEscalations(), "应上报给本轮组织者");
        assertEquals("抱歉。" + NOTICE, turn.appendNotices("抱歉。"));
        assertTrue(turn.handoffReason().orElseThrow().contains("轮次用尽"));
        assertEquals(1.0, registry.counter("customerwork.agent.iters.exhausted").count(), "观测照记，恰好一次");
        assertEquals(true, auditOf("agent-iters-exhausted").get("delegated"));
    }

    @Test
    @DisplayName("对用户说话的调用（上下文会话就是本轮会话）照旧转人工并追加说明")
    void userFacingInvocationStillActs() {
        ConversationTurn turn = ConversationTurn.open(USER_SESSION);

        List<AgentEvent> out = run(ctx(USER_SESSION, turn), exhaustedTurn());

        Msg result = ((AgentResultEvent) out.get(out.size() - 1)).getResult();
        assertTrue(result.getTextContent().endsWith(NOTICE), result.getTextContent());
        verify(handoffService, times(1)).create(eq(USER_SESSION), contains("轮次用尽"));
        assertFalse(turn.hasEscalations(), "对用户说话的调用自己处置，不需要上报");
        assertEquals(false, auditOf("agent-iters-exhausted").get("delegated"));
    }

    // ---------- 辅助 ----------

    private List<AgentEvent> run(RuntimeContext ctx, List<AgentEvent> downstream) {
        return middleware.onAgent(null, ctx, new AgentInput(List.of(msg("用户问题"))),
            in -> Flux.fromIterable(downstream)).collectList().block();
    }

    /** 与 {@code LoopGuardMiddlewareTest#exhaustedTurn} 同形：推理轮 → 轮次用尽 → 收尾逐片流式 → 最终结果。 */
    private List<AgentEvent> exhaustedTurn() {
        List<AgentEvent> events = new ArrayList<>(answerBlock("r1", "我先查一下。"));
        events.addAll(toolCall("c-r1", "queryOrder", "{\"orderId\":\"SO-1\"}", null));
        events.add(new ExceedMaxItersEvent("", 10, 10));
        events.addAll(answerBlock("r2", "抱歉。"));
        events.add(new AgentResultEvent(Msg.builder().role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text("抱歉。").build())
            .generateReason(GenerateReason.MAX_ITERATIONS).build()));
        return events;
    }

    private static List<AgentEvent> answerBlock(String replyId, String text) {
        return List.of(new ModelCallStartEvent(replyId), new TextBlockStartEvent(replyId, "text"),
            new TextBlockDeltaEvent(replyId, "text", text), new TextBlockEndEvent(replyId, "text"),
            new ModelCallEndEvent(replyId, null));
    }

    private static List<AgentEvent> toolCall(String callId, String name, String args, String source) {
        List<AgentEvent> events = List.of(new ToolCallStartEvent("r", callId, name),
            new ToolCallDeltaEvent("r", callId, name, args), new ToolCallEndEvent("r", callId, name));
        return source == null ? events : events.stream().map(e -> e.withSource(source)).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditOf(String type) {
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(auditSink).record(eq(type), fields.capture());
        return fields.getValue();
    }

    private static RuntimeContext ctx(String session, ConversationTurn turn) {
        RuntimeContext ctx = RuntimeContext.builder().userId("tenant").sessionId(session).build();
        if (turn != null) {
            ctx.put(ConversationTurn.class, turn);
        }
        return ctx;
    }

    private static Msg msg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text(text).build()).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
