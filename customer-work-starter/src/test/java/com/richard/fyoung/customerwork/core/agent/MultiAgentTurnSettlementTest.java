package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.middleware.LoopGuardMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.message.ContentBlock;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /consult} 这一轮由编排器收尾：专家转不出来时，用户拿到的最终答复里带且只带一次去向说明，
 * 转人工落在用户自己的会话上、只转一次。
 *
 * <p>用真实的 {@code ReActAgent}、真实的治理装配与脚本模型驱动整条 fanout / sequential 链路。此前专家的循环守卫
 * 在 {@code <会话>#mas-consult} 上转人工（一张没人能接到用户的工单），说明追加在专家的中间结论上、
 * 进了归纳器的提示词——归纳器改写时可以把它丢掉。本测试的归纳器就是一个会丢掉它的归纳器。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class MultiAgentTurnSettlementTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SESSION = "u1:conv-consult";
    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();
    /** 归纳器的产出：一段不带任何说明的改写——真实模型完全可能这样做。 */
    private static final String REDUCED = "综合结论：订单已发货，政策问题暂未查到明确答复。";
    private static final String EXPERT_ANSWER = "订单 SO-1 已发货。";
    private static final String EXPERT_SUMMARY = "抱歉，暂时没查到相关政策。";

    private HandoffService handoffService;
    private AuditSink auditSink;
    private MeterRegistry registry;
    private CustomerWorkProperties props;
    /** 归纳器收到的提示词：检查专家的中间结论里有没有夹带说明。 */
    private final List<String> reducerPrompts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        registry = new SimpleMeterRegistry();
        props = new CustomerWorkProperties();
        props.getMultiAgent().setMaxIters(2);
        props.getMultiAgent().setRoutingEnabled(false);
    }

    @Test
    @DisplayName("fanout：一位专家转不出来，说明接在归纳后的最终答复末尾且只一次，归纳器的提示词里没有它")
    void fanoutAppendsNoticeAfterReduceAndHandsOffUserSession() {
        String reply = orchestrator(model(Behavior.KNOWLEDGE_LOOPS)).consult(SESSION, "我的订单和发票").block(TIMEOUT);

        assertEquals(REDUCED + NOTICE, reply, "说明应由编排器接在最终答复末尾");
        assertEquals(1, reducerPrompts.size());
        assertFalse(reducerPrompts.get(0).contains(NOTICE.trim()),
            "专家的中间结论不应夹带说明，否则归纳器可以改写或丢掉它：" + reducerPrompts.get(0));
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
        verify(handoffService, never()).create(argThat(s -> s.contains("#mas-")), anyString());
        assertEquals(1.0, registry.counter("customerwork.agent.iters.exhausted").count(), "转不出来的专家记一次");
    }

    @Test
    @DisplayName("不归纳时三位专家全转不出来：拼接结果里说明只出现一次，转人工只转一次")
    void allExpertsExhaustedStillOneNoticeAndOneHandoff() {
        props.getMultiAgent().setReduceEnabled(false);

        String reply = orchestrator(model(Behavior.ALL_LOOP)).consult(SESSION, "帮我看看").block(TIMEOUT);

        assertEquals(1, occurrences(reply, NOTICE.trim()), "说明只出现一次：" + reply);
        assertTrue(reply.endsWith(NOTICE), reply);
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
        assertEquals(3.0, registry.counter("customerwork.agent.iters.exhausted").count(), "每位专家各记一次");
    }

    @Test
    @DisplayName("sequential：链上有专家转不出来，最终结论带且只带一次说明，转人工落在用户会话")
    void sequentialSettlesOnce() {
        props.getMultiAgent().setMode("sequential");

        String reply = orchestrator(model(Behavior.KNOWLEDGE_LOOPS)).consult(SESSION, "政策").block(TIMEOUT);

        assertTrue(reply.endsWith(NOTICE), reply);
        assertEquals(1, occurrences(reply, NOTICE.trim()), reply);
        verify(handoffService, times(1)).create(eq(SESSION), contains("轮次用尽"));
    }

    @Test
    @DisplayName("专家都答完时编排器不追加说明、不转人工")
    void normalConsultIsUntouched() {
        String reply = orchestrator(model(Behavior.ALL_ANSWER)).consult(SESSION, "我的订单").block(TIMEOUT);

        assertEquals(REDUCED, reply);
        verify(handoffService, never()).create(anyString(), anyString());
    }

    /** 答复安全闸门在专家身上命中：同样不在派生会话上转人工，而是由编排器落到用户会话。 */
    @Test
    @DisplayName("专家给出未经核实的资金结论：转人工落在用户会话上，不落在派生会话上")
    void selfCorrectionHandoffLandsOnUserSession() {
        String reply = orchestrator(model(Behavior.AFTER_SALES_CLAIMS_REFUND)).consult(SESSION, "退款到了吗")
            .block(TIMEOUT);

        assertEquals(REDUCED, reply, "闸门的否定澄清留在专家结论里交给归纳器，最终答复不另加");
        verify(handoffService, times(1)).create(eq(SESSION), contains("未经核实的资金结论"));
        verify(handoffService, never()).create(argThat(s -> s.contains("#mas-")), anyString());
    }

    /**
     * 分诊器的输出交给编排器挑专家，不交给用户：它的 {@code summary} 转述了用户说的「已退款」，
     * 框架又把结构化参数写进了最终消息的文本——这不是对用户的断言，不该记成未经核实的资金结论。
     */
    @Test
    @DisplayName("分诊器摘要转述了「已退款」：不记成未经核实的资金结论、不转人工")
    void routerSummaryQuotingRefundIsNotAClaim() {
        props.getMultiAgent().setRoutingEnabled(true);
        props.getMultiAgent().setFastRouteEnabled(false);

        orchestrator(model(Behavior.ROUTER_QUOTES_REFUND)).consult(SESSION, "你们说已退款但我一直没到账")
            .block(TIMEOUT);

        verify(handoffService, never()).create(anyString(), anyString());
        assertEquals(0.0, registry.counter("customerwork.selfcorrection.unverified.claim", "stage", "final").count(),
            "转述用户原话不是对用户的断言");
        verify(auditSink, never()).record(eq("self-correction-unverified-claim"), anyMap());
    }

    // ---------- 装配 ----------

    private MultiAgentOrchestrator orchestrator(Model model) {
        LoopGuardMiddleware loopGuard = new LoopGuardMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
        SelfCorrectionMiddleware selfCorrection = new SelfCorrectionMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
        @SuppressWarnings("unchecked")
        ObjectProvider<MiddlewareBase> middlewares = mock(ObjectProvider.class);
        when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(selfCorrection, loopGuard));
        AgentGovernanceAssembler assembler =
            new AgentGovernanceAssembler(props, new TenantResolver(props), middlewares, null);
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(model, props, new MockOrderBackend(),
            new MockAfterSalesBackend(), new MockKnowledgeBackend(), assembler);
        orchestrator.setHandoffService(handoffService);
        return orchestrator;
    }

    private enum Behavior { KNOWLEDGE_LOOPS, ALL_LOOP, ALL_ANSWER, AFTER_SALES_CLAIMS_REFUND, ROUTER_QUOTES_REFUND }

    /**
     * 按系统提示词分派的离线脚本模型：归纳器记下提示词后给出固定改写；转不出来的专家每轮都调工具，
     * 框架的收尾调用不带工具清单，据此给出收尾；其余专家直接作答。
     */
    private Model model(Behavior behavior) {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                String system = systemPrompt(messages);
                if (system.contains("归纳器")) {
                    reducerPrompts.add(messages.get(messages.size() - 1).getTextContent());
                    return text(REDUCED);
                }
                if (system.contains("分诊器")) {
                    return routerDecision();
                }
                boolean loops = behavior == Behavior.ALL_LOOP
                    || behavior == Behavior.KNOWLEDGE_LOOPS && system.contains("政策咨询专家");
                if (!loops) {
                    boolean claims = behavior == Behavior.AFTER_SALES_CLAIMS_REFUND && system.contains("售后与退款专家");
                    return text(claims ? "您的退款已到账，请注意查收。" : EXPERT_ANSWER);
                }
                if (tools == null || tools.isEmpty()) {
                    return text(EXPERT_SUMMARY);
                }
                String tool = tools.get(0).getName();
                return Flux.just(response(List.of(new ToolUseBlock("call-" + calls.incrementAndGet(), tool,
                    Map.of("query", "发票", "orderId", "SO-1"))), "tool_calls"));
            }

            @Override
            public String getModelName() {
                return "scripted-consult-model";
            }
        };
    }

    /** 分诊器的结构化调用：参数既给解析后的 input 也给原始 JSON，框架按原始 JSON 校验。 */
    private static Flux<ChatResponse> routerDecision() {
        String summary = "用户反映客服称已退款但款项一直未到账";
        Map<String, Object> decision = Map.of("intent", "refund", "orderId", "", "urgent", true, "summary", summary);
        String raw = "{\"response\":{\"intent\":\"refund\",\"orderId\":\"\",\"urgent\":true,\"summary\":\""
            + summary + "\"}}";
        return Flux.just(response(List.of(new ToolUseBlock("call-router", "generate_response",
            Map.of("response", decision), raw, null)), "tool_calls"));
    }

    private static String systemPrompt(List<Msg> messages) {
        return messages.stream().filter(m -> m.getRole() == MsgRole.SYSTEM)
            .map(Msg::getTextContent).collect(Collectors.joining("\n"));
    }

    private static Flux<ChatResponse> text(String text) {
        return Flux.just(response(List.of(TextBlock.builder().text(text).build()), "stop"));
    }

    private static ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder().id(UUID.randomUUID().toString()).content(content)
            .usage(new ChatUsage(1, 1, 0.0)).finishReason(finishReason).build();
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int from = text.indexOf(part); from >= 0; from = text.indexOf(part, from + part.length())) {
            count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
