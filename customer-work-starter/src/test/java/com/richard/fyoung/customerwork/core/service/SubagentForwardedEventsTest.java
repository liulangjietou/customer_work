package com.richard.fyoung.customerwork.core.service;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.AgentGovernanceAssembler;
import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.agent.HarnessAgentFactory;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.core.middleware.MaskingMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SelfCorrectionMiddleware;
import com.richard.fyoung.customerwork.core.middleware.SensitiveWordMiddleware;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.observability.LoggingAuditSink;
import com.richard.fyoung.customerwork.safety.security.SensitiveDataMasker;
import com.richard.fyoung.customerwork.safety.sensitiveword.InMemorySensitiveWordStore;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWord;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordAction;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordCategory;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordFilter;
import com.richard.fyoung.customerwork.safety.sensitiveword.SensitiveWordHitSink;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Harness 子智能体经 {@code agent_spawn} 同步执行时，它的正文不能变成主智能体对用户的答复——
 * 用真实 {@link HarnessAgent}、与生产同一份治理装配（自我纠错 + 脱敏 + 敏感词）和真实的
 * {@link CustomerServiceService#chatStream} 驱动。
 *
 * <h3>框架事实（2.0.3 反编译）</h3>
 * <ul>
 *   <li>{@code AgentSpawnTool#execLocalSync} 把「打 source 标记」的父发射器塞进子智能体的转发上下文，
 *       子智能体的细粒度事件（正文增量、工具调用……）于是<b>只</b>进入主智能体的事件流，带
 *       {@link AgentEvent#getSource()}；它们经过的是主智能体的 {@code onAgent} 链
 *       （{@code ReActAgent} 的事件汇在该链最内层），不经过主智能体的 {@code onActing} / {@code onModelCall}。</li>
 *   <li>{@link AgentEvent#withSource} 是可变 setter；中间件用三参构造重建的
 *       {@link TextBlockDeltaEvent} 不带 source——改写即「洗白」成主智能体的正文。</li>
 *   <li>框架给每个文本块的 blockId 都是常量 {@code "text"}，块的身份在 replyId 上。</li>
 * </ul>
 *
 * <p>红了先看失败信息说的是哪一层：H5 看到的正文（消费方）、转发正文还认不认得出来源（出站中间件），
 * 还是资金断言闸门拦错了对象（自我纠错）。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class SubagentForwardedEventsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String SESSION = "u1:conv-subagent";
    private static final String EXPERT = "AfterSalesExpert";
    private static final String PHONE = "13812345678";
    /** 子智能体没查任何真实状态就说「已退款」，还带了一个手机号。 */
    private static final String CHILD_UNVERIFIED = "您的订单已退款，售后专员电话 " + PHONE + "。";
    private static final String CHILD_VERIFIED = "查询结果：退款审核已通过，款项已退。";
    private static final String CLARIFICATION =
        new CustomerWorkProperties().getHooks().getSelfCorrection().getClarification();

    @TempDir
    Path workspace;

    private HandoffService handoffService;
    private CustomerWorkProperties props;

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        props = new CustomerWorkProperties();
        props.getHooks().getMasking().setEnabled(true);
        props.getSensitiveWord().setEnabled(true);
    }

    @Test
    @DisplayName("H5：子智能体转发进来的正文（含未核实的「已退款」与手机号）不进用户答复，也不触发资金闸门")
    void childTextNeverReachesCustomer() {
        String parentFinal = "已为您登记退款申请，审核通过后会原路退回。";
        String answer;
        try (HarnessAgent parent = parent(false, parentFinal)) {
            answer = String.join("", service(parent).chatStream(SESSION, "我的退款怎么样了？")
                .collectList().block(TIMEOUT));
        }

        assertEquals(parentFinal, answer,
            "用户只该看到主智能体自己的答复——子智能体的正文是交给主智能体的中间材料，"
                + "主智能体会拿它的结果组织回答；混进来就是同一件事说两遍，还夹着未核实的说法");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("出站中间件：子智能体的正文照常脱敏，但仍认得出来源——主智能体自己的正文逐字不变、不被追加澄清")
    void outboundMiddlewaresKeepForwardedSource() {
        String parentFinal = "已为您登记退款申请，审核通过后会原路退回。";
        List<AgentEvent> events;
        try (HarnessAgent parent = parent(false, parentFinal)) {
            events = parent.streamEvents(List.of(userMsg()), assembler().contextFor(SESSION))
                .collectList().block(TIMEOUT);
        }

        String childText = text(events.stream().filter(e -> e.getSource() != null
            && e.getSource().contains(EXPERT)));
        String parentText = text(events.stream().filter(e -> e.getSource() == null));
        assertTrue(childText.contains("售后专员电话"),
            "框架事实：同步 spawn 时子智能体的正文带 source 转发进主流；改写它的中间件必须沿用 source，"
                + "否则 admin 的子智能体卡片与 H5 都分不出来。实际转发正文=" + childText + "，事件=" + describe(events));
        assertFalse(childText.contains(PHONE), "转发正文照常脱敏（admin 的子智能体卡片展示它）：" + childText);
        assertEquals(parentFinal, parentText, "主智能体自己的正文不应混入子智能体的正文，也不应被追加澄清");
        assertFalse(events.stream().filter(TextBlockDeltaEvent.class::isInstance)
                .anyMatch(e -> ((TextBlockDeltaEvent) e).getDelta().contains(CLARIFICATION.trim())),
            "子智能体没交给用户的话不该触发资金闸门的澄清");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("自我纠错：子智能体查过退款进度，主智能体转述「已退款」——这是转述真实状态，放行")
    void childEvidenceBacksParentRelay() {
        String parentFinal = "已为您查询：您的订单已退款。";
        String answer;
        try (HarnessAgent parent = parent(true, parentFinal)) {
            answer = String.join("", service(parent).chatStream(SESSION, "我的退款怎么样了？")
                .collectList().block(TIMEOUT));
        }

        assertEquals(parentFinal, answer,
            "子智能体的查询结果随工具结果交回主智能体，同一轮里主智能体的转述有依据；"
                + "把子智能体的工具调用排除在依据之外，会把正确的转述当成编造拦下并转人工");
        verify(handoffService, never()).create(anyString(), anyString());
    }

    @Test
    @DisplayName("自我纠错：子智能体凭空说「已退款」、主智能体照搬——拦在主智能体的正文上，恰好一次")
    void parentRelayOfUnverifiedChildClaimIsBlockedOnce() {
        String parentFinal = "专家确认您的订单已退款。";
        String answer;
        try (HarnessAgent parent = parent(false, parentFinal)) {
            answer = String.join("", service(parent).chatStream(SESSION, "我的退款怎么样了？")
                .collectList().block(TIMEOUT));
        }

        assertEquals("专家确认您的订单" + CLARIFICATION, answer,
            "判的是用户读到的正文：主智能体说到关键词处截住并补澄清。子智能体的那句用户根本没看到，"
                + "若由它触发，澄清会补在用户没见过的话后面，主智能体的答复则被整段吞掉");
        verify(handoffService, times(1)).create(eq(SESSION), anyString());
    }

    // ---------- 装配 ----------

    /** 与生产同一条客服流式路径：Harness 路由打开，工厂交出这里建好的主智能体。 */
    private CustomerServiceService service(HarnessAgent parent) {
        CustomerServiceAgentFactory agentFactory = mock(CustomerServiceAgentFactory.class);
        AgentGovernanceAssembler assembler = assembler();
        when(agentFactory.contextFor(anyString())).thenAnswer(inv -> assembler.contextFor(inv.getArgument(0)));
        HarnessAgentFactory harnessAgentFactory = mock(HarnessAgentFactory.class);
        when(harnessAgentFactory.createHarnessAgent(anyString())).thenReturn(parent);
        CustomerWorkProperties serviceProps = new CustomerWorkProperties();
        serviceProps.getHarness().setEnabled(true);
        return new CustomerServiceService(agentFactory, harnessAgentFactory, mock(SessionStateManager.class),
            serviceProps, new MemorySubjectResolver(), provider(null), provider(null), provider(null),
            provider(null), provider(null), provider(null), provider(null));
    }

    /** 主智能体：经治理装配，再升级为 Harness 并挂上编排器产出的售后专家（与 {@code HarnessAgentFactory} 相同）。 */
    private HarnessAgent parent(boolean childQueriesProgress, String parentFinal) {
        AgentGovernanceAssembler assembler = assembler();
        Model model = model(childQueriesProgress, parentFinal);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        PermissionContextState permission = PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        ReActAgent.Builder inner = ReActAgent.builder()
            .name("CustomerServiceAgent")
            .sysPrompt("你是主客服智能体，售后问题交给专家子智能体。")
            .model(model)
            .toolkit(new Toolkit())
            .stateStore(stateStore)
            .permissionContext(permission)
            .maxIters(5);
        assembler.applyTo(inner);
        ReActAgent expert = new MultiAgentOrchestrator(model, props, new MockOrderBackend(),
            new MockAfterSalesBackend(), new MockKnowledgeBackend(), assembler).buildSpecialists().stream()
            .filter(a -> EXPERT.equals(a.getName())).findFirst().orElseThrow();
        return HarnessAgent.Builder.fromAgent(inner.build())
            .stateStore(stateStore)
            .permissionContext(permission)
            .generateOptions(GenerateOptions.builder().build())
            .workspace(workspace)
            .subagentFactory(EXPERT, parentCtx -> expert)
            .disableDynamicSubagents()
            .build();
    }

    /** 出站方向与 C 端相同的三道处理：自我纠错、脱敏、敏感词（敏感词开启即逐片重发正文）。 */
    private AgentGovernanceAssembler assembler() {
        MeterRegistry registry = new SimpleMeterRegistry();
        SelfCorrectionMiddleware selfCorrection = new SelfCorrectionMiddleware(props, provider(handoffService),
            provider((AuditSink) null), provider(registry));
        MaskingMiddleware masking = new MaskingMiddleware(props, new SensitiveDataMasker(props));
        InMemorySensitiveWordStore store = new InMemorySensitiveWordStore();
        store.save(SensitiveWord.of("傻瓜", SensitiveWordCategory.CUSTOM, SensitiveWordAction.MASK));
        SensitiveWordMiddleware sensitiveWords = new SensitiveWordMiddleware(props,
            new SensitiveWordFilter(store, '*', SensitiveWordAction.BLOCK), new LoggingAuditSink(),
            provider(registry), provider((SensitiveWordHitSink) null));
        @SuppressWarnings("unchecked")
        ObjectProvider<MiddlewareBase> middlewares = mock(ObjectProvider.class);
        when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(selfCorrection, masking, sensitiveWords));
        return new AgentGovernanceAssembler(props, new TenantResolver(props), middlewares, null);
    }

    /**
     * 父子共用的脚本模型（生产上也是同一个模型 Bean），按系统提示词分派：主智能体首轮调 {@code agent_spawn}、
     * 之后作答；售后专家按需先查退款进度，再给出结论。
     */
    private Model model(boolean childQueriesProgress, String parentFinal) {
        AtomicInteger parentCalls = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                String system = messages.stream().filter(m -> m.getRole() == MsgRole.SYSTEM)
                    .map(Msg::getTextContent).collect(Collectors.joining("\n"));
                if (system.contains("主客服智能体")) {
                    boolean spawnOffered = tools != null && tools.stream().anyMatch(t -> "agent_spawn".equals(t.getName()));
                    if (parentCalls.getAndIncrement() == 0 && spawnOffered) {
                        return Flux.just(toolCall("agent_spawn", Map.of("agent_id", EXPERT,
                            "task", "查询该用户的退款进度", "timeout_seconds", 30)));
                    }
                    return Flux.just(text(parentFinal));
                }
                if (childQueriesProgress && childCalls.getAndIncrement() == 0) {
                    return Flux.just(toolCall("queryRefundProgress", Map.of("orderId", "O1001")));
                }
                return Flux.just(text(childQueriesProgress ? CHILD_VERIFIED : CHILD_UNVERIFIED));
            }

            @Override
            public String getModelName() {
                return "scripted-subagent-model";
            }
        };
    }

    private static ChatResponse toolCall(String name, Map<String, Object> args) {
        return response(List.of(new ToolUseBlock(UUID.randomUUID().toString(), name, args, json(args), null)),
            "tool_calls");
    }

    private static ChatResponse text(String text) {
        return response(List.of(TextBlock.builder().text(text).build()), "stop");
    }

    private static ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder().id(UUID.randomUUID().toString()).content(content)
            .usage(new ChatUsage(1, 1, 0.0)).finishReason(finishReason).build();
    }

    private static String json(Map<String, Object> args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            throw new IllegalStateException("serialize tool args failed", e);
        }
    }

    private static String text(Stream<AgentEvent> events) {
        return events.filter(TextBlockDeltaEvent.class::isInstance)
            .map(e -> ((TextBlockDeltaEvent) e).getDelta()).collect(Collectors.joining());
    }

    private static Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).textContent("我的退款怎么样了？").build();
    }

    private static List<String> describe(List<AgentEvent> events) {
        return events.stream().map(e -> e.getType() + "(source=" + e.getSource() + ")").toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
