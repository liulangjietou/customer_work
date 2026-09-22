package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class JevRefundRiskMiddlewareTest {

    private static final String USER_TEXT = "三天不退钱我就去 315 曝光你们";

    private HandoffService handoff;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        handoff = mock(HandoffService.class);
        ctx = JevTestSupport.ctx();
    }

    @Test
    @DisplayName("高风险退款：额外转人工，退款工具照常执行")
    void highRiskHandsOff() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.85);

        run(live(client), refundCall());

        verify(handoff).create(eq("u1:conv-1"), contains("高风险"));
        String state = client.states().get(0);
        assertTrue(state.contains(USER_TEXT) && state.contains("1999"), "用户原话与退款参数都应送去判定：" + state);
    }

    @Test
    @DisplayName("正常退款：不转人工")
    void normalRefundDoesNothing() {
        run(live(JevTestSupport.noul(0.2)), refundCall());

        verify(handoff, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("非退款工具：根本不问 Jev")
    void otherToolsSkipJev() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.99);

        run(live(client), new ToolUseBlock("t1", "queryOrder", Map.of("orderId", "O1")));

        assertEquals(0, client.calls());
    }

    @Test
    @DisplayName("影子模式：展示「线上会额外转人工」，但不转")
    void shadowReportsOnly() {
        JevRefundRiskMiddleware mw = new JevRefundRiskMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.noul(0.9))),
            JevTestSupport.provider(handoff), JevRunMode.SHADOW);

        List<AgentEvent> out = run(mw, refundCall());

        verify(handoff, never()).create(anyString(), anyString());
        Map<String, Object> decision = ((CustomEvent) out.stream().filter(JevDecisionEvent::isDecision)
            .findFirst().orElseThrow()).getValue();
        assertTrue(String.valueOf(decision.get(JevDecisionEvent.KEY_ACTION)).contains("额外转人工"));
        assertTrue(String.valueOf(decision.get(JevDecisionEvent.KEY_ACTION)).contains("不做任何放行"),
            "展示文案必须让人看清：Jev 从不放行退款");
        assertEquals(false, decision.get(JevDecisionEvent.KEY_EXECUTED));
    }

    private JevRefundRiskMiddleware live(JevTestSupport.StubClient client) {
        return new JevRefundRiskMiddleware(JevTestSupport.provider(JevTestSupport.service(client)),
            JevTestSupport.provider(handoff));
    }

    private List<AgentEvent> run(JevRefundRiskMiddleware mw, ToolUseBlock call) {
        mw.onAgent(null, ctx, new AgentInput(List.of(user(USER_TEXT))), in -> Flux.empty()).blockLast();
        return mw.onActing(null, ctx, new ActingInput(List.of(call)), in -> Flux.empty()).collectList().block();
    }

    private static ToolUseBlock refundCall() {
        return new ToolUseBlock("t1", "submitRefund", Map.of("orderId", "O1", "amount", "1999", "reason", "质量问题"));
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }
}
