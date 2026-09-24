package com.richard.fyoung.customerwork.core.middleware;

import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.agent.AgentGovernanceAssembler;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.observability.AuditSink;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Harness 子智能体转不出来时，循环守卫认得清是谁转不出来——用真实 {@link HarnessAgent} 与多专家编排器
 * 产出的专家（与 {@code HarnessAgentFactory} 注册子智能体的方式相同）驱动。
 *
 * <h3>框架事实（2.0.3 反编译 + 本类钉住）</h3>
 * <ul>
 *   <li><b>同步 spawn</b>：{@code AgentSpawnTool#execLocalSync} 把「打 source 标记」的父发射器塞进子智能体的
 *       转发上下文，子智能体 {@code CallExecution#publishEvent} 于是<b>只</b>往父流发细粒度事件，
 *       它自己的中间件链只看得到开始 / 结果 / 结束。所以子智能体转不出来，只有父智能体这一层看得见——
 *       此前父智能体的循环守卫把它当成自己的：父智能体照常答完了也转人工、也追加「轮次上限」的说明，
 *       说明还补进了子智能体转发进来的文本块。</li>
 *   <li><b>异步 spawn</b>（{@code timeout_seconds=0}）：子智能体在后台任务里跑、不转发，事件走它自己的中间件链；
 *       它的上下文由 {@code RuntimeContext.builder(父上下文)} 派生——属性原样复制、会话换成 {@code sub-<uuid>}。
 *       此前它的循环守卫就在这个 {@code sub-} 会话上转人工，说明追加进交还给父模型的结果。</li>
 * </ul>
 *
 * <p>红了先看是哪条框架事实变了（失败信息里写明），再决定是改守卫还是改断言。</p>
 *
 * @author owlzhangfq@gmail.com
 */
class LoopGuardSubagentForwardingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String SESSION = "u1:conv-harness";
    private static final String EXPERT = "KnowledgeExpert";
    private static final String PARENT_FINAL = "发票政策我已为您核实：订单签收后可在线申请电子发票。";
    private static final String CHILD_SUMMARY = "抱歉，暂时没能查到发票政策。";
    private static final String NOTICE =
        new CustomerWorkProperties().getHooks().getLoopGuard().getExhaustedNotice();

    @TempDir
    Path workspace;

    private HandoffService handoffService;
    private AuditSink auditSink;
    private MeterRegistry registry;
    private CustomerWorkProperties props;
    /** 每个 Agent 自己的中间件链看到的事件：agent 名 → 事件。 */
    private final List<Map.Entry<String, AgentEvent>> seenByChain = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        handoffService = mock(HandoffService.class);
        auditSink = mock(AuditSink.class);
        registry = new SimpleMeterRegistry();
        props = new CustomerWorkProperties();
        props.getMultiAgent().setMaxIters(2);
    }

    @Test
    @DisplayName("同步 spawn：子智能体转不出来、父智能体照常答完——不转人工、不追加说明，恰好记一次且记在子智能体名下")
    void syncSpawnedChildExhaustionDoesNotTakeOverParentTurn() {
        List<AgentEvent> events;
        try (HarnessAgent parent = parent(30)) {
            events = parent.streamEvents(List.of(userMsg()), assembler().contextFor(SESSION))
                .collectList().block(TIMEOUT);
        }

        assertTrue(events.stream().anyMatch(e -> e instanceof ExceedMaxItersEvent
                && e.getSource() != null && e.getSource().contains(EXPERT)),
            "框架事实：子智能体的轮次用尽应带 source 转发进父流，实际=" + describe(events));
        assertFalse(seenByChain.stream().anyMatch(e -> EXPERT.equals(e.getKey())
                && e.getValue() instanceof ExceedMaxItersEvent),
            "框架事实：同步 spawn 时子智能体自己的中间件链看不到它的轮次用尽（只发往父流）。"
                + "红了说明框架改了转发方式，子智能体自己的守卫也会记一次，需重新核对「恰好记一次」");

        String parentText = events.stream().filter(e -> e.getSource() == null)
            .filter(TextBlockDeltaEvent.class::isInstance)
            .map(e -> ((TextBlockDeltaEvent) e).getDelta()).collect(Collectors.joining());
        assertEquals(PARENT_FINAL, parentText, "父智能体自己的正文不应被追加说明");
        AgentResultEvent result = (AgentResultEvent) events.stream()
            .filter(e -> e instanceof AgentResultEvent && e.getSource() == null).reduce((a, b) -> b).orElseThrow();
        assertEquals(PARENT_FINAL, result.getResult().getTextContent());
        assertFalse(events.stream().filter(TextBlockDeltaEvent.class::isInstance)
                .anyMatch(e -> ((TextBlockDeltaEvent) e).getDelta().contains(NOTICE.trim())),
            "说明不应补进任何文本块（含子智能体转发进来的）");
        verify(handoffService, never()).create(anyString(), anyString());

        assertEquals(1.0, registry.counter("customerwork.agent.iters.exhausted").count(), "恰好记一次");
        Map<String, Object> audit = exhaustedAudit();
        assertTrue(String.valueOf(audit.get("agent")).contains(EXPERT), "应记在子智能体名下：" + audit);
        assertEquals(true, audit.get("delegated"));
    }

    @Test
    @DisplayName("异步 spawn：子智能体在 sub- 会话上转不出来——不在那里转人工，交还父模型的结果里也不带说明")
    void asyncSpawnedChildDoesNotHandOffDerivedSession() throws InterruptedException {
        try (HarnessAgent parent = parent(0)) {
            parent.streamEvents(List.of(userMsg()), assembler().contextFor(SESSION)).collectList().block(TIMEOUT);

            verify(auditSink, timeout(TIMEOUT.toMillis())).record(eq("agent-iters-exhausted"),
                org.mockito.ArgumentMatchers.anyMap());
            Msg childResult = awaitChildResult();

            assertFalse(childResult.getTextContent().contains(NOTICE.trim()),
                "交还父模型的子智能体结果不应带去向说明：" + childResult.getTextContent());
        }
        verify(handoffService, never()).create(anyString(), anyString());
        Map<String, Object> audit = exhaustedAudit();
        assertEquals(EXPERT, audit.get("agent"));
        assertTrue(String.valueOf(audit.get("session")).startsWith("sub-"),
            "框架事实：异步子智能体跑在派生的 sub- 会话上，实际=" + audit.get("session"));
        assertEquals(true, audit.get("delegated"), "派生上下文复制了本轮信息，子智能体应认出自己是内部调用");
    }

    // ---------- 装配 ----------

    /** 父智能体：与生产一样经治理装配（含循环守卫），再升级为 Harness 并挂上编排器产出的专家。 */
    private HarnessAgent parent(int spawnTimeoutSeconds) {
        AgentGovernanceAssembler assembler = assembler();
        Model model = model(spawnTimeoutSeconds);
        InMemoryAgentStateStore stateStore = new InMemoryAgentStateStore();
        PermissionContextState permission = PermissionContextState.builder().mode(PermissionMode.BYPASS).build();
        ReActAgent.Builder inner = ReActAgent.builder()
            .name("CustomerServiceAgent")
            .sysPrompt("你是主客服智能体，政策问题交给专家子智能体。")
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

    private AgentGovernanceAssembler assembler() {
        LoopGuardMiddleware loopGuard = new LoopGuardMiddleware(props,
            provider(handoffService), provider(auditSink), provider(registry));
        MiddlewareBase probe = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                            Function<AgentInput, Flux<AgentEvent>> next) {
                return next.apply(input).doOnNext(e -> seenByChain.add(Map.entry(agent.getName(), e)));
            }
        };
        @SuppressWarnings("unchecked")
        ObjectProvider<MiddlewareBase> middlewares = mock(ObjectProvider.class);
        when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(probe, loopGuard));
        return new AgentGovernanceAssembler(props, new TenantResolver(props), middlewares, null);
    }

    /**
     * 父子共用的脚本模型（生产上也是同一个模型 Bean），按系统提示词分派：父智能体首轮调 {@code agent_spawn}、
     * 之后作答；专家每轮都调工具直到轮次用尽，框架的收尾调用不带工具清单，据此给出收尾。
     */
    private Model model(int spawnTimeoutSeconds) {
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
                        Map<String, Object> args = Map.of("agent_id", EXPERT, "task", "查询发票政策",
                            "timeout_seconds", spawnTimeoutSeconds);
                        return Flux.just(response(List.of(new ToolUseBlock(UUID.randomUUID().toString(),
                            "agent_spawn", args, json(args), null)), "tool_calls"));
                    }
                    return Flux.just(response(List.of(TextBlock.builder().text(PARENT_FINAL).build()), "stop"));
                }
                if (tools == null || tools.isEmpty()) {
                    return Flux.just(response(List.of(TextBlock.builder().text(CHILD_SUMMARY).build()), "stop"));
                }
                Map<String, Object> query = Map.of("query", "发票");
                return Flux.just(response(List.of(new ToolUseBlock("child-" + childCalls.incrementAndGet(),
                    tools.get(0).getName(), query, json(query), null)), "tool_calls"));
            }

            @Override
            public String getModelName() {
                return "scripted-harness-model";
            }
        };
    }

    /** 异步子智能体在后台跑完后，它自己的中间件链看到的最终结果。 */
    private Msg awaitChildResult() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (Map.Entry<String, AgentEvent> seen : seenByChain) {
                if (EXPERT.equals(seen.getKey()) && seen.getValue() instanceof AgentResultEvent result) {
                    return result.getResult();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("异步子智能体未在时限内产出结果，链上事件=" + seenByChain);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exhaustedAudit() {
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(auditSink).record(eq("agent-iters-exhausted"), fields.capture());
        return fields.getValue();
    }

    private static Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).textContent("开发票有什么政策？").build();
    }

    private static String json(Map<String, Object> args) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(args);
        } catch (Exception e) {
            throw new IllegalStateException("serialize tool args failed", e);
        }
    }

    private static ChatResponse response(List<ContentBlock> content, String finishReason) {
        return ChatResponse.builder().id(UUID.randomUUID().toString()).content(content)
            .usage(new ChatUsage(1, 1, 0.0)).finishReason(finishReason).build();
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
