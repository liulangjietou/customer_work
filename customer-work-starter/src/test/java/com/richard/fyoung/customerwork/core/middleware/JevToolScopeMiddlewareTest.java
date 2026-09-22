package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.typesafe.JevDecisionEvent;
import com.richard.fyoung.customerwork.capability.typesafe.JevRunMode;
import com.richard.fyoung.customerwork.capability.typesafe.JevTestSupport;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolGroup;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JevToolScopeMiddlewareTest {

    /** 未分组的基础工具（如 Harness 自带的文件工具）。 */
    private static final String UNGROUPED = "listFiles";

    private Toolkit toolkit;
    private ReActAgent agent;
    private List<ToolSchema> tools;
    private RuntimeContext ctx;

    @BeforeEach
    void setUp() {
        toolkit = mock(Toolkit.class);
        when(toolkit.getActiveGroups()).thenReturn(List.of("order", "after_sales", "knowledge", ToolRegistrar.GROUP_HUMAN));
        group("order", "queryOrder");
        group("after_sales", "submitRefund");
        group("knowledge", "searchKnowledge");
        group(ToolRegistrar.GROUP_HUMAN, "transferToHuman");
        agent = mock(ReActAgent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        tools = List.of(tool("queryOrder"), tool("submitRefund"), tool("searchKnowledge"),
            tool("transferToHuman"), tool(UNGROUPED));
        ctx = JevTestSupport.ctx();
    }

    /**
     * 转人工组必须永远保留：把它收掉等于把用户困在智能体里。
     * 未分组的基础工具也保留：它们不属于任何业务意图，收掉只会让 Harness 能力莫名消失。
     */
    @Test
    @DisplayName("只保留映射到的业务组 + 转人工组 + 未分组工具")
    void keepsMappedGroupsHumanAndUngrouped() {
        List<ToolSchema> narrowed = JevToolScopeMiddleware.narrow(toolkit, tools, List.of("order", "knowledge"));

        assertEquals(List.of("queryOrder", "searchKnowledge", "transferToHuman", UNGROUPED), names(narrowed));
    }

    @Test
    @DisplayName("没有任何工具被收掉、拿不到 Toolkit：返回 null 表示原样透传")
    void returnsNullWhenNothingToNarrow() {
        assertNull(JevToolScopeMiddleware.narrow(toolkit, tools, List.of("order", "after_sales", "knowledge")));
        assertNull(JevToolScopeMiddleware.narrow(null, tools, List.of("order")));
    }

    @Test
    @DisplayName("高置信意图：本轮模型只看得到收窄后的工具")
    void liveNarrowsReasoningTools() {
        JevToolScopeMiddleware mw = live(JevTestSupport.calmTurn("order", 0.95));
        runTurn(mw);

        assertEquals(List.of("queryOrder", "searchKnowledge", "transferToHuman", UNGROUPED),
            names(captureReasoning(mw).tools()));
    }

    @Test
    @DisplayName("置信度低于门槛或意图为 other：工具面原封不动")
    void keepsToolsWhenUnsure() {
        for (JevTestSupport.StubClient client : List.of(JevTestSupport.calmTurn("order", 0.8),
            JevTestSupport.calmTurn("other", 0.99), JevTestSupport.unavailable())) {
            ctx = JevTestSupport.ctx();
            JevToolScopeMiddleware mw = live(client);
            runTurn(mw);

            ReasoningInput input = new ReasoningInput(List.of(), tools, null);
            assertSame(input, capture(mw, input));
        }
    }

    /**
     * 意图必须选一个在 LIVE 下<b>真的会收掉工具</b>的：order 只保留订单与知识库组，会收掉退款工具。
     * 若选 refund（映射到三组、恰好覆盖桩里全部业务工具），收不收窄结果都一样，
     * 「影子模式改了工具面」这条断言就成了恒真——变异测试抓到过这一次。
     */
    @Test
    @DisplayName("影子模式：展示收窄结论，但不改模型看到的工具")
    void shadowReportsWithoutNarrowing() {
        JevToolScopeMiddleware mw = new JevToolScopeMiddleware(
            JevTestSupport.provider(JevTestSupport.service(JevTestSupport.calmTurn("order", 0.97))),
            JevRunMode.SHADOW);

        List<AgentEvent> out = runTurn(mw);
        ReasoningInput input = new ReasoningInput(List.of(), tools, null);

        assertSame(input, capture(mw, input), "影子模式改了工具面");
        Map<String, Object> decision = ((CustomEvent) out.stream().filter(JevDecisionEvent::isDecision)
            .findFirst().orElseThrow()).getValue();
        assertEquals(JevDecisionEvent.POINT_TOOL_SCOPE, decision.get(JevDecisionEvent.KEY_POINT));
        assertTrue(String.valueOf(decision.get(JevDecisionEvent.KEY_ACTION)).contains("order、knowledge"));
        assertEquals(false, decision.get(JevDecisionEvent.KEY_EXECUTED));
        assertEquals(0.97, decision.get(JevDecisionEvent.KEY_CONFIDENCE));
    }

    /**
     * 情绪与工具收窄共用一次调用。Jev 不可用时两个中间件都拿到「无决策」，
     * 但后台只该看到一个降级节点。
     */
    @Test
    @DisplayName("与情绪判定同轮降级时，只展示一个降级节点")
    void sharedDegradedNodeShownOnce() {
        JevTestSupport.StubClient client = JevTestSupport.unavailable();
        JevEscalationMiddleware escalation = new JevEscalationMiddleware(
            JevTestSupport.provider(JevTestSupport.service(client)), JevTestSupport.provider(null), JevRunMode.SHADOW);
        JevToolScopeMiddleware scope = new JevToolScopeMiddleware(
            JevTestSupport.provider(JevTestSupport.service(client)), JevRunMode.SHADOW);
        AgentInput input = new AgentInput(List.of(user("你好")));

        List<AgentEvent> out = escalation.onAgent(null, ctx, input,
            in -> scope.onAgent(null, ctx, in, inner -> Flux.empty())).collectList().block();

        assertEquals(1, out.stream().filter(JevDecisionEvent::isDecision).count(), out.toString());
    }

    // ---------- 辅助 ----------

    private JevToolScopeMiddleware live(JevTestSupport.StubClient client) {
        return new JevToolScopeMiddleware(JevTestSupport.provider(JevTestSupport.service(client)));
    }

    private List<AgentEvent> runTurn(JevToolScopeMiddleware mw) {
        return mw.onAgent(agent, ctx, new AgentInput(List.of(user("帮我查一下"))), in -> Flux.empty())
            .collectList().block();
    }

    private ReasoningInput captureReasoning(JevToolScopeMiddleware mw) {
        return capture(mw, new ReasoningInput(List.of(), tools, null));
    }

    private ReasoningInput capture(JevToolScopeMiddleware mw, ReasoningInput input) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        mw.onReasoning(agent, ctx, input, in -> {
            seen.set(in);
            return Flux.empty();
        }).blockLast();
        return seen.get();
    }

    private void group(String name, String... toolNames) {
        ToolGroup group = mock(ToolGroup.class);
        when(group.getTools()).thenReturn(Set.of(toolNames));
        when(toolkit.getToolGroup(name)).thenReturn(group);
    }

    private static ToolSchema tool(String name) {
        return ToolSchema.builder().name(name).description(name).parameters(Map.of()).build();
    }

    private static List<String> names(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName).toList();
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(text).build()).build();
    }
}
