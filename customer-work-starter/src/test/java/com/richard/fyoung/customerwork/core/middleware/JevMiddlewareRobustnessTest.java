package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffTicket;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionService;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import com.richard.fyoung.customerwork.capability.typesafe.SystemOneClient;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「Jev 不可用时 Agent 照常可用」的机器防线。
 *
 * <p>覆盖 C 端自动装配的那份实例（{@code JevRunMode.LIVE}）。
 * 对三个决策中间件逐一组合 Jev 的每种表现，断言四条不变量：</p>
 * <ol>
 *   <li><b>下游恰好被调用一次</b>：少一次这一轮没有回复，多一次 Agent 被执行两遍；</li>
 *   <li>Agent 的事件原样、按序透传；</li>
 *   <li>C 端（LIVE）事件流里不出现任何决策事件——那是内部实现细节；</li>
 *   <li>Agent 自身的错误不被吞掉——中间件只能兜自己的故障。</li>
 * </ol>
 */
class JevMiddlewareRobustnessTest {

    private static final List<AgentEvent> AGENT_EVENTS = List.of(
        new TextBlockDeltaEvent("r", "b", "您好，"),
        new TextBlockDeltaEvent("r", "b", "已为您查询"),
        new AgentResultEvent(msg(MsgRole.ASSISTANT, "您好，已为您查询")));

    static Stream<Arguments> jevBehaviours() {
        return Stream.of(
            Arguments.of("未开启", null),
            Arguments.of("不可用（超时/熔断/失败）", JevTestSupport.unavailable()),
            Arguments.of("传输层桩抛错", JevTestSupport.failing()),
            Arguments.of("高置信要求人工", JevTestSupport.furiousTurn()),
            Arguments.of("明显不满", JevTestSupport.upsetTurn()),
            Arguments.of("高置信意图", JevTestSupport.calmTurn("order", 0.99)),
            Arguments.of("退款高风险", JevTestSupport.noul(0.99)));
    }

    @ParameterizedTest(name = "情绪升级 · Jev {0}")
    @MethodSource("jevBehaviours")
    @DisplayName("情绪升级：下游恰好一次、事件原样透传、不外露决策")
    void escalationNeverBreaksAgent(String label, SystemOneClient client) {
        assertAgentUnharmed(new JevEscalationMiddleware(jev(client), handoff()), label);
    }

    @ParameterizedTest(name = "工具收窄 · Jev {0}")
    @MethodSource("jevBehaviours")
    @DisplayName("工具收窄：下游恰好一次、事件原样透传、不外露决策")
    void toolScopeNeverBreaksAgent(String label, SystemOneClient client) {
        assertAgentUnharmed(new JevToolScopeMiddleware(jev(client)), label);
    }

    @ParameterizedTest(name = "退款风险 · Jev {0}")
    @MethodSource("jevBehaviours")
    @DisplayName("退款风险：退款工具照常执行、下游恰好一次、不外露决策")
    void refundRiskNeverBlocksTool(String label, SystemOneClient client) {
        JevRefundRiskMiddleware middleware = new JevRefundRiskMiddleware(jev(client), handoff());
        RuntimeContext ctx = JevTestSupport.ctx();
        middleware.onAgent(null, ctx, userInput(), in -> Flux.empty()).blockLast();
        AtomicInteger calls = new AtomicInteger();
        ActingInput acting = new ActingInput(List.of(new ToolUseBlock("t1", "submitRefund",
            Map.of("orderId", "O1", "amount", "99", "reason", "不想要了"))));

        List<AgentEvent> out = middleware.onActing(null, ctx, acting, in -> {
            calls.incrementAndGet();
            return Flux.fromIterable(AGENT_EVENTS);
        }).collectList().block();

        assertEquals(1, calls.get(), label + "：退款工具必须恰好执行一次");
        assertEquals(AGENT_EVENTS, out, label + "：工具事件必须原样透传，C 端不外露决策事件");
    }

    /**
     * 下游返回空事件流（例如更内层的中间件短路）时，下游也只能被调一次。
     * 用 {@code switchIfEmpty(next)} 兜底的写法在这里会让 Agent 被执行第二遍——
     * 下游非空的用例照不出来，必须单独测。
     */
    @ParameterizedTest(name = "下游空流 · Jev {0}")
    @MethodSource("jevBehaviours")
    @DisplayName("下游返回空事件流时，下游仍只被调用一次")
    void emptyDownstreamIsNotInvokedTwice(String label, SystemOneClient client) {
        for (MiddlewareBase middleware : List.of(new JevEscalationMiddleware(jev(client), handoff()),
            new JevToolScopeMiddleware(jev(client)))) {
            AtomicInteger calls = new AtomicInteger();
            middleware.onAgent(null, JevTestSupport.ctx(), userInput(), in -> {
                calls.incrementAndGet();
                return Flux.empty();
            }).blockLast();
            assertEquals(1, calls.get(), label + " / " + middleware.getClass().getSimpleName() + "：下游被调了多次");
        }
    }

    @ParameterizedTest(name = "Agent 自身出错 · Jev {0}")
    @MethodSource("jevBehaviours")
    @DisplayName("Agent 自身的错误原样抛出，不被中间件吞掉")
    void agentErrorsPropagate(String label, SystemOneClient client) {
        IllegalStateException boom = new IllegalStateException("model down");
        for (MiddlewareBase middleware : List.of(new JevEscalationMiddleware(jev(client), handoff()),
            new JevToolScopeMiddleware(jev(client)))) {
            StepVerifier.create(middleware.onAgent(null, JevTestSupport.ctx(), userInput(), in -> Flux.error(boom)))
                .expectErrorMatches(e -> e == boom)
                .verify();
        }
    }

    private void assertAgentUnharmed(MiddlewareBase middleware, String label) {
        AtomicInteger calls = new AtomicInteger();
        Function<AgentInput, Flux<AgentEvent>> next = in -> {
            calls.incrementAndGet();
            return Flux.fromIterable(AGENT_EVENTS);
        };

        List<AgentEvent> out = middleware.onAgent(null, JevTestSupport.ctx(), userInput(), next)
            .collectList().block();

        assertEquals(1, calls.get(), label + "：下游必须恰好被调用一次");
        assertEquals(AGENT_EVENTS, out, label + "：Agent 事件必须原样按序透传");
        assertFalse(out.stream().anyMatch(JevDecisionEvent::isDecision), label + "：C 端不得出现决策事件");
    }

    private static ObjectProvider<JevDecisionService> jev(SystemOneClient client) {
        return JevTestSupport.provider(client == null ? null : JevTestSupport.service(client));
    }

    private static ObjectProvider<HandoffService> handoff() {
        HandoffService handoff = mock(HandoffService.class);
        when(handoff.create(anyString(), anyString()))
            .thenReturn(new HandoffTicket("TK-1", "u1:conv-1", "r", System.currentTimeMillis()));
        return JevTestSupport.provider(handoff);
    }

    private static AgentInput userInput() {
        return new AgentInput(List.of(msg(MsgRole.USER, "我要退款，你们太差劲了")));
    }

    private static Msg msg(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }
}
