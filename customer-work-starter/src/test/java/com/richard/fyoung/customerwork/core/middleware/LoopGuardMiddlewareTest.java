package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 智能体转不出来时的观测与兜底。
 *
 * @author owlzhangfq@gmail.com
 */
class LoopGuardMiddlewareTest {

    private HandoffService handoffService;
    private AuditSink auditSink;
    private MeterRegistry registry;
    private LoopGuardMiddleware middleware;

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        registry = new SimpleMeterRegistry();
        middleware = build(new CustomerWorkProperties());
    }

    /**
     * 用户已经在这一轮里等了十次模型调用，收到的是一句模型自己写的含糊道歉，
     * 然后没有下文——这条测试钉住「至少要有人接手」。
     */
    @Test
    @DisplayName("迭代耗尽时转人工，并在收尾回复上追加去向说明")
    void handsOffAndAppendsNoticeOnExhausted() {
        List<AgentEvent> out = run(Flux.just(
            new ExceedMaxItersEvent("r", 10, 10),
            new AgentResultEvent(msg("抱歉，我暂时没能处理好这个问题。"))));

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        String text = result.getResult().getTextContent();
        assertTrue(text.startsWith("抱歉，我暂时没能处理好这个问题。"),
            "框架生成的收尾里可能有对用户有用的中间结论，应当保留：" + text);
        assertTrue(text.contains("转接人工客服"), "必须告诉用户接下来会发生什么：" + text);

        verify(handoffService).create(anyString(), contains("轮次用尽"));
        verify(auditSink).record(eq("agent-iters-exhausted"), anyMap());
        assertEquals(1.0, registry.counter("customerwork.agent.iters.exhausted").count());
    }

    /**
     * 参数必须计入签名。
     *
     * <p>同一个工具用<b>不同</b>参数连查五个订单是正常业务；只按工具名计数会把它误报成循环，
     * 而误报几次之后这个指标就没人看了。</p>
     */
    @Test
    @DisplayName("同一工具查不同参数不算循环")
    void differentArgumentsAreNotALoop() {
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.addAll(toolCall("c" + i, "queryOrder", "{\"orderId\":\"SO-" + i + "\"}"));
        }

        run(Flux.fromIterable(events));

        verify(auditSink, never()).record(eq("agent-tool-call-repeated"), anyMap());
        assertEquals(0.0, registry.counter("customerwork.agent.toolcall.repeated").count());
    }

    @Test
    @DisplayName("同一工具用同样参数连调三次判为疑似循环")
    void identicalCallsHitThreshold() {
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            events.addAll(toolCall("c" + i, "queryOrder", "{\"orderId\":\"SO-1\"}"));
        }

        run(Flux.fromIterable(events));

        verify(auditSink).record(eq("agent-tool-call-repeated"), anyMap());
        assertEquals(1.0, registry.counter("customerwork.agent.toolcall.repeated").count());
    }

    /**
     * 只在恰好触达阈值的那一次告警。
     *
     * <p>继续涨下去每轮报一次，会把日志与指标刷成噪音，而它们本来是用来发现异常的。</p>
     */
    @Test
    @DisplayName("超过阈值后不再重复告警")
    void alertsOnlyOnceAtThreshold() {
        List<AgentEvent> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            events.addAll(toolCall("c" + i, "queryOrder", "{\"orderId\":\"SO-1\"}"));
        }

        run(Flux.fromIterable(events));

        verify(auditSink, times(1)).record(eq("agent-tool-call-repeated"), anyMap());
    }

    @Test
    @DisplayName("正常一轮完全透传，不产生任何告警与转人工")
    void passesThroughNormalTurn() {
        List<AgentEvent> events = new ArrayList<>(toolCall("c1", "queryOrder", "{\"orderId\":\"SO-1\"}"));
        events.add(new AgentResultEvent(msg("您的订单正在配送中。")));

        List<AgentEvent> out = run(Flux.fromIterable(events));

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        assertEquals("您的订单正在配送中。", result.getResult().getTextContent(),
            "正常回复不该被追加任何提示");
        verify(handoffService, never()).create(anyString(), anyString());
        verify(auditSink, never()).record(anyString(), anyMap());
    }

    @Test
    @DisplayName("关闭时完全不介入")
    void doesNothingWhenDisabled() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getHooks().getLoopGuard().setEnabled(false);
        LoopGuardMiddleware off = build(props);

        List<AgentEvent> out = off.onAgent(null, ctx(), input(), in -> Flux.just(
            new ExceedMaxItersEvent("r", 10, 10),
            new AgentResultEvent(msg("原文"))))
            .collectList().block();

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        assertEquals("原文", result.getResult().getTextContent());
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("转人工失败不影响给用户的说明")
    void handoffFailureDoesNotBreakReply() {
        when(handoffService.create(anyString(), anyString()))
            .thenThrow(new IllegalStateException("工单服务不可用"));

        List<AgentEvent> out = run(Flux.just(
            new ExceedMaxItersEvent("r", 10, 10),
            new AgentResultEvent(msg("抱歉。"))));

        AgentResultEvent result = (AgentResultEvent) out.get(out.size() - 1);
        assertTrue(result.getResult().getTextContent().contains("转接人工客服"),
            "转人工失败时用户仍应看到去向说明");
    }

    // ---------- 辅助 ----------

    private List<AgentEvent> run(Flux<AgentEvent> downstream) {
        return middleware.onAgent(null, ctx(), input(), in -> downstream).collectList().block();
    }

    /** 一次完整的工具调用事件三件套：名字、参数增量、结束。 */
    private List<AgentEvent> toolCall(String callId, String name, String args) {
        return List.of(
            new ToolCallStartEvent("r", callId, name),
            new ToolCallDeltaEvent("r", callId, name, args),
            new ToolCallEndEvent("r", callId, name));
    }

    private LoopGuardMiddleware build(CustomerWorkProperties props) {
        return new LoopGuardMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
    }

    private Msg msg(String text) {
        return Msg.builder().role(MsgRole.ASSISTANT)
            .content(TextBlock.builder().text(text).build()).build();
    }

    private AgentInput input() {
        return new AgentInput(List.of(msg("用户问题")));
    }

    private RuntimeContext ctx() {
        RuntimeContext ctx = mock(RuntimeContext.class);
        when(ctx.getSessionId()).thenReturn("u1:conv-1");
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
