package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.infra.config.properties.TypeSafeProperties;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevDecisionServiceTest {

    /**
     * ReAct 循环里每迭代一次都会经过中间件，情绪与工具收窄两个决策点也共用同一次调用。
     * 不缓存的话一轮对话会打出 N 次请求。
     */
    @Test
    @DisplayName("同一轮里多次取入站决策，只发一次请求")
    void turnDecisionIsCachedPerContext() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("order", 0.95);
        JevDecisionService service = JevTestSupport.service(client);
        RuntimeContext ctx = JevTestSupport.ctx();

        TurnDecision first = service.decideTurn(ctx, "我的订单").block();
        TurnDecision second = service.decideTurn(ctx, "我的订单").block();

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(1, client.calls());
    }

    @Test
    @DisplayName("不可用也只问一次：失败结果同样被缓存，不会每次迭代都重试")
    void emptyResultIsCachedToo() {
        JevTestSupport.StubClient client = JevTestSupport.unavailable();
        JevDecisionService service = JevTestSupport.service(client);
        RuntimeContext ctx = JevTestSupport.ctx();

        assertNull(service.decideTurn(ctx, "你好").block());
        assertNull(service.decideTurn(ctx, "你好").block());
        assertEquals(1, client.calls());
    }

    @Test
    @DisplayName("不同轮次（不同上下文）各问各的")
    void differentTurnsAskSeparately() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("order", 0.95);
        JevDecisionService service = JevTestSupport.service(client);

        service.decideTurn(JevTestSupport.ctx(), "a").block();
        service.decideTurn(JevTestSupport.ctx(), "b").block();

        assertEquals(2, client.calls());
    }

    @Test
    @DisplayName("空内容不发请求")
    void blankTextSkips() {
        JevTestSupport.StubClient client = JevTestSupport.calmTurn("order", 0.95);
        JevDecisionService service = JevTestSupport.service(client);

        assertNull(service.decideTurn(JevTestSupport.ctx(), " ").block());
        assertNull(service.judgePersonalContext("", "答").block());
        assertEquals(0, client.calls());
    }

    @Test
    @DisplayName("退款风险判定把用户原话与退款参数一起送去判")
    void refundRiskIncludesArguments() {
        JevTestSupport.StubClient client = JevTestSupport.noul(0.7);
        JevDecisionService service = JevTestSupport.service(client);

        NoulVerdict verdict = service.judgeRefundRisk("不退我就去曝光你们", "9999", "不想要了").block();

        assertEquals(0.7, verdict.probability());
        String state = client.states().get(0);
        assertTrue(state.contains("不退我就去曝光你们") && state.contains("9999") && state.contains("不想要了"), state);
    }

    @Test
    @DisplayName("情绪升级关闭时入站决策改用短超时（工具收窄是优化类，不该拖慢对话）")
    void turnTimeoutFollowsEscalationSwitch() {
        TypeSafeProperties props = new TypeSafeProperties();
        java.util.List<java.time.Duration> seen = new java.util.ArrayList<>();
        SystemOneClient recording = (state, questions, timeout) -> {
            seen.add(timeout);
            return reactor.core.publisher.Mono.empty();
        };
        JevDecisionService service = JevTestSupport.service(recording, props);

        service.decideTurn("x").block();
        props.getEscalation().setEnabled(false);
        service.decideTurn("x").block();

        assertEquals(java.time.Duration.ofMillis(props.getTimeoutMs()), seen.get(0));
        assertEquals(java.time.Duration.ofMillis(props.getFastTimeoutMs()), seen.get(1));
    }
}
