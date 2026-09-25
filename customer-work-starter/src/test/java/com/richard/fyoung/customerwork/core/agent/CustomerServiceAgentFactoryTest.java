package com.richard.fyoung.customerwork.core.agent;

import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.handoff.HandoffService;
import com.richard.fyoung.customerwork.core.dto.IntentResult;
import com.richard.fyoung.customerwork.core.dto.KnowledgeCitation;
import com.richard.fyoung.customerwork.core.memory.FactLog;
import com.richard.fyoung.customerwork.core.memory.InMemoryLongTermMemoryStore;
import com.richard.fyoung.customerwork.core.memory.LongTermMemoryProvider;
import com.richard.fyoung.customerwork.core.memory.LongTermMemoryStore;
import com.richard.fyoung.customerwork.core.memory.MemorySubjectResolver;
import com.richard.fyoung.customerwork.core.middleware.ChatTerminalCaptureMiddleware;
import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.core.service.ChatTurnEvent;
import com.richard.fyoung.customerwork.core.service.ChatTurnFinalizer;
import com.richard.fyoung.customerwork.core.service.ChatTurnService;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.support.InMemoryTestFactLog;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.calllog.ToolKindRegistry;
import com.richard.fyoung.customerwork.data.chatlog.ChatLogService;
import com.richard.fyoung.customerwork.data.chatlog.InMemoryChatMessageStore;
import com.richard.fyoung.customerwork.data.rag.KnowledgeProvider;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.NacosPromptService;
import com.richard.fyoung.customerwork.infra.config.PermissionConfig;
import com.richard.fyoung.customerwork.tool.HigressToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.McpToolkitConfigurer;
import com.richard.fyoung.customerwork.tool.ToolRegistrar;
import com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.MockComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MockMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.MockOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.MockProductBackend;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Agent 工厂单测：工具分组注册完整性、Meta-Tool 开关、租户解析。无需 Spring 上下文。
 * @author owlzhangfq@gmail.com
 */
class CustomerServiceAgentFactoryTest {

    private final Model model = mock(Model.class);
    private final LongTermMemoryStore store = new InMemoryLongTermMemoryStore();

    private CustomerServiceAgentFactory factory(CustomerWorkProperties props) {
        return factory(props, new KnowledgeProvider(props));
    }

    private CustomerServiceAgentFactory factory(CustomerWorkProperties props, KnowledgeProvider knowledgeProvider) {
        return factory(props, knowledgeProvider, null);
    }

    private CustomerServiceAgentFactory factory(CustomerWorkProperties props, KnowledgeProvider knowledgeProvider,
        ObjectProvider<MiddlewareBase> middlewares) {
        FactLog factLog = new InMemoryTestFactLog(false);
        return new CustomerServiceAgentFactory(
            model, props,
            new LongTermMemoryProvider(props, store, factLog),
            knowledgeProvider,
            new McpToolkitConfigurer(props, new ToolKindRegistry()),
            new HigressToolkitConfigurer(props),
            new ToolRegistrar(
                new MockOrderBackend(),
                new MockAfterSalesBackend(),
                new MockKnowledgeBackend(),
                new MockProductBackend(),
                new MockMemberBackend(),
                new MockComplaintBackend(),
                new PendingApprovalService(),
                new HandoffService(),
                null),
            new InMemoryAgentStateStore(),
            new PermissionConfig().permissionContextState(props),
            new NacosPromptService(props),
            new TenantResolver(props),
            new MemorySubjectResolver(),
            new ToolKindRegistry(),
            // 治理装配器：中间件装配已收敛到这一处，工厂不再各自持有 Hook 列表与 MeterRegistry
            new AgentGovernanceAssembler(props,
                new TenantResolver(props), middlewares, null),
            null);   // 无 MySQL 技能物化器（本类只覆盖 classpath / filesystem 仓库）
    }

    @Test
    void ragTool_shouldCaptureActualMetadataWithoutParsingToolText() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRag().setEnabled(true);
        var knowledge = mock(Knowledge.class);
        var provider = mock(KnowledgeProvider.class);
        Mockito.when(provider.get()).thenReturn(knowledge);
        var document = new Document(new DocumentMetadata(
            TextBlock.builder().text("可核对的原文，不含来源标记").build(),
            "refund-policy", "chunk-1", Map.of("knowledgeBase", "售后政策")));
        document.setScore(0.91);
        var retrievalTenant = new AtomicReference<String>();
        Mockito.when(knowledge.retrieve(ArgumentMatchers.eq("退款"),
            ArgumentMatchers.any())).thenAnswer(invocation -> {
                retrievalTenant.set(TenantContext.get());
                return reactor.core.publisher.Mono.just(List.of(document));
            });
        var f = factory(props, provider);
        var agent = f.createAgent("source-capture");
        var context = f.contextFor("source-capture");
        context.put(AgentInvocationIdentity.class,
            new AgentInvocationIdentity("tenant-a", QuotaSubjectType.USER, "customer-a", true));
        var capture = new ChatTerminalCapture();
        context.put(ChatTerminalCapture.class, capture);
        var call = ToolCallParam.builder()
            .toolUseBlock(ToolUseBlock.builder().id("retrieval-1")
                .name("retrieve_knowledge").input(Map.of("query", "退款"))
                .content("{\"query\":\"退款\"}").build())
            .input(Map.of("query", "退款")).agent(agent).runtimeContext(context).build();
        var result = TenantContext.callWith("stale-b", () -> {
            var output = agent.getToolkit().callTool(call).block(Duration.ofSeconds(10));
            assertEquals("stale-b", TenantContext.require(), "工具调用不得污染调用方上下文");
            return output;
        });
        assertEquals("tenant-a", retrievalTenant.get(), "工具必须使用原生调用快照，不能读迟到线程的租户");
        Assertions.assertNotNull(result);
        org.assertj.core.api.Assertions.assertThat(result.getOutput().toString()).contains("可核对的原文");
        Mockito.verify(knowledge).retrieve(ArgumentMatchers.eq("退款"),
            ArgumentMatchers.any());
        var standard = new Toolkit();
        standard.registerTool(new KnowledgeRetrievalTools(knowledge));
        assertEquals(standard.getTool("retrieve_knowledge").getParameters(),
            agent.getToolkit().getTool("retrieve_knowledge").getParameters());
        assertEquals(standard.getTool("retrieve_knowledge").getDescription(),
            agent.getToolkit().getTool("retrieve_knowledge").getDescription());
        assertEquals(List.of(new KnowledgeCitation(
            "售后政策", "refund-policy", "chunk-1", 0.91)), capture.citations());
    }

    @Test
    @SuppressWarnings("unchecked")
    void realAgentTurn_shouldPersistActualSourcesThroughNativeContextAcrossThreads() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRag().setEnabled(true);
        props.getMemory().setLongTermEnabled(false);
        props.getSkill().setEnabled(false);
        props.getObservability().setTraceEnabled(false);
        var knowledge = mock(Knowledge.class);
        var provider = mock(KnowledgeProvider.class);
        Mockito.when(provider.get()).thenReturn(knowledge);
        var document = new Document(new DocumentMetadata(
            TextBlock.builder().text("退款需要核对订单进度").build(),
            "refund-policy", "chunk-1", Map.of("knowledgeBase", "售后政策")));
        document.setScore(0.91);
        Mockito.when(knowledge.retrieve(ArgumentMatchers.anyString(),
            ArgumentMatchers.any())).thenReturn(reactor.core.publisher.Mono.just(List.of(document))
                .delayElement(Duration.ofMillis(5)));
        var middlewares = (ObjectProvider<MiddlewareBase>)
            mock(ObjectProvider.class);
        Mockito.when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(
            new ChatTerminalCaptureMiddleware()));
        var modelCalls = new AtomicInteger();
        Mockito.when(model.getModelName()).thenReturn("answer-evidence-offline");
        Mockito.when(model.stream(ArgumentMatchers.anyList(),
            ArgumentMatchers.anyList(), ArgumentMatchers.any())).thenAnswer(inv -> {
                int step = modelCalls.incrementAndGet();
                ContentBlock block = step == 1
                    ? ToolUseBlock.builder().id("rag-1").name("retrieve_knowledge")
                        .input(Map.of("query", "退款")).content("{\"query\":\"退款\"}").build()
                    : TextBlock.builder().text("请先核对订单进度").build();
                return reactor.core.publisher.Flux.just(ChatResponse.builder()
                    .id("reply-" + step).content(List.of(block))
                    .usage(new ChatUsage(2, 3, 0.1))
                    .finishReason(step == 1 ? "tool_calls" : "stop").build());
            });
        var f = factory(props, provider, middlewares);
        var agent = f.createAgent("evidence-real-turn");
        var customerService = mock(CustomerServiceService.class);
        Mockito.when(customerService.chatStream("evidence-real-turn", "退款"))
            .thenReturn(agent.call("退款", f.contextFor("evidence-real-turn"))
                .map(Msg::getTextContent).flux());
        var store = new InMemoryChatMessageStore();
        var service = new ChatTurnService(customerService,
            new ChatTurnFinalizer(
                new ChatLogService(store)));
        var result = service.stream("evidence-real-turn", "退款", null)
            .ofType(ChatTurnEvent.Completed.class)
            .blockLast(Duration.ofSeconds(15)).completion();
        assertEquals(2, modelCalls.get());
        assertEquals("请先核对订单进度", result.message().content());
        assertEquals("MODEL_STOP", result.terminal().finishReason());
        assertEquals("refund-policy", result.terminal().citations().get(0).documentId());
        assertEquals(result.terminal().citations(), store.findByMessageId(result.message().messageId())
            .orElseThrow().citations());
        Mockito.verify(knowledge).retrieve(ArgumentMatchers.eq("退款"),
            ArgumentMatchers.any());
    }

    @Test
    void buildToolkit_shouldExposeAllBusinessTools() {
        Toolkit toolkit = factory(new CustomerWorkProperties()).buildToolkit();
        Set<String> toolNames = toolkit.getToolNames();

        assertTrue(toolNames.contains("queryOrder"), "缺少 queryOrder: " + toolNames);
        assertTrue(toolNames.contains("queryLogistics"), "缺少 queryLogistics: " + toolNames);
        assertTrue(toolNames.contains("searchKnowledge"), "缺少 searchKnowledge: " + toolNames);
        assertTrue(toolNames.contains("checkRefundEligibility"), "缺少 checkRefundEligibility: " + toolNames);
        assertTrue(toolNames.contains("submitRefund"), "缺少 submitRefund: " + toolNames);
        assertTrue(toolNames.contains("transferToHuman"), "缺少 transferToHuman: " + toolNames);

        assertTrue(toolkit.getToolSchemas().size() >= 6,
            "暴露给模型的工具 Schema 数量异常: " + toolkit.getToolSchemas().size());
    }

    @Test
    void buildToolkit_metaTool_shouldAddExtraToolsWhenEnabled() {
        CustomerWorkProperties off = new CustomerWorkProperties();
        off.getAgent().setMetaToolEnabled(false);
        int withoutMeta = factory(off).buildToolkit().getToolNames().size();

        CustomerWorkProperties on = new CustomerWorkProperties();
        on.getAgent().setMetaToolEnabled(true);
        int withMeta = factory(on).buildToolkit().getToolNames().size();

        assertTrue(withMeta > withoutMeta,
            "开启 Meta-Tool 后应注册额外的元工具，with=" + withMeta + " without=" + withoutMeta);
    }

    @Test
    void resolveTenant_shouldSplitOnDelimiter() {
        CustomerServiceAgentFactory f = factory(new CustomerWorkProperties());
        assertEquals("tenantA", f.resolveTenant("tenantA:conv-1"));
        assertEquals("tenantA", f.resolveTenant("tenantA:conv-2"));
        assertEquals("u1001", f.resolveTenant("u1001"));
        assertEquals("default", f.resolveTenant(""));
    }

    /**
     * 端到端回归（AgentScope 2.0 GA 空状态覆盖激活组问题）：配置状态存储时，新会话首次调用
     * 实际传给模型的工具清单必须包含业务工具。此前框架用 fresh state 的空 activatedGroups
     * 覆盖 Toolkit 激活组，模型只能收到未分组基础工具（表现为"没有订单查询工具"）。
     */
    @Test
    void createAgent_freshSessionWithStateStore_businessToolsShouldReachModel() {
        CustomerServiceAgentFactory f = factory(new CustomerWorkProperties());
        ReActAgent agent = f.createAgent("tenantX:conv-tool-surface");

        AtomicReference<List<ToolSchema>> captured =
            new AtomicReference<>();
        Mockito.when(model.getModelName()).thenReturn("capture-mock");
        Mockito.when(model.stream(
                ArgumentMatchers.anyList(),
                ArgumentMatchers.anyList(),
                ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                captured.set(inv.getArgument(1));
                // 捕获后即终止推理循环（工具清单在模型调用前已确定，报错不影响断言目标）
                return reactor.core.publisher.Flux.error(new IllegalStateException("capture-only"));
            });

        agent.call("帮我查订单 20260613001", f.contextFor("tenantX:conv-tool-surface"))
            .onErrorResume(e -> {
                System.out.println("[capture-test] call terminated: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
                return reactor.core.publisher.Mono.empty();
            })
            .block(Duration.ofSeconds(15));

        assertTrue(captured.get() != null && !captured.get().isEmpty(), "模型未收到任何工具 schema");
        Set<String> names = captured.get().stream()
            .map(ToolSchema::getName)
            .collect(Collectors.toSet());
        assertTrue(names.contains("queryOrder"), "新会话下订单工具未到达模型层: " + names);
        assertTrue(names.contains("searchKnowledge"), "知识库工具未到达模型层: " + names);
        assertTrue(names.contains("transferToHuman"), "转人工工具未到达模型层: " + names);
    }

    // ==================== 意图分类器专用 Agent ====================

    /** 框架结构化输出注入的工具名（fallback 路径，见 agentscope-java #1852）。 */
    private static final String STRUCTURED_OUTPUT_TOOL = "generate_response";

    /** 离线模型：第一轮调用结构化输出工具给出 refund，之后回纯文本收尾；每次调用都记录入参。 */
    private void scriptStructuredIntentModel(List<List<ToolSchema>> toolsSeen, List<List<Msg>> messagesSeen) {
        AtomicInteger step = new AtomicInteger();
        Mockito.when(model.getModelName()).thenReturn("intent-offline");
        Mockito.when(model.stream(ArgumentMatchers.anyList(), ArgumentMatchers.anyList(), ArgumentMatchers.any()))
            .thenAnswer(inv -> {
                messagesSeen.add(List.copyOf(inv.getArgument(0)));
                toolsSeen.add(List.copyOf(inv.getArgument(1)));
                boolean first = step.incrementAndGet() % 2 == 1;
                ContentBlock block = first
                    ? ToolUseBlock.builder().id("gr-" + step.get()).name(STRUCTURED_OUTPUT_TOOL)
                        // 框架把目标 Schema 包在 response 字段下（ReActAgent#createStructuredOutputTool）
                        .input(Map.of("response",
                            Map.of("intent", "refund", "orderId", "", "urgent", false, "summary", "退款")))
                        .content("{\"response\":{\"intent\":\"refund\",\"orderId\":\"\","
                            + "\"urgent\":false,\"summary\":\"退款\"}}")
                        .build()
                    : TextBlock.builder().text("done").build();
                return reactor.core.publisher.Flux.just(ChatResponse.builder()
                    .id("intent-" + step.get()).content(List.of(block))
                    .usage(new ChatUsage(1, 1, 0.0))
                    .finishReason(first ? "tool_calls" : "stop").build());
            });
    }

    private static Set<String> toolNames(List<ToolSchema> schemas) {
        return schemas.stream().map(ToolSchema::getName).collect(Collectors.toSet());
    }

    /**
     * 分类器只允许看到结构化输出工具。
     *
     * <p>此前分类器复用 {@code createAgent}，模型每次分类都拿到全部业务工具：{@code cancelOrder} /
     * {@code modifyAddress} / {@code submitReturn} / {@code fileComplaint} 这类写操作没有任何闸门
     * （Permission 的 ask 规则只覆盖 submitRefund 与 transferToHuman，且默认关闭），
     * 一条发往 /intent 的消息就可能真的办了业务；同时每次分类都要付全部工具 schema 的 token。
     * 这里把长期记忆、RAG、任务清单、Meta-Tool 全部打开，任何一路漏进来都会红。</p>
     */
    @Test
    void intentClassifier_shouldExposeOnlyStructuredOutputToolToModel() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getAgent().setMetaToolEnabled(true);
        CustomerServiceAgentFactory f = factory(props);
        List<List<ToolSchema>> toolsSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
        scriptStructuredIntentModel(toolsSeen, new java.util.concurrent.CopyOnWriteArrayList<>());

        ReActAgent classifier = f.createIntentClassifierAgent("intent:tenantA:conv-1");
        Msg reply = classifier.call("我要退款，顺便把订单取消", IntentResult.class,
            f.contextFor("intent:tenantA:conv-1")).block(Duration.ofSeconds(15));

        assertTrue(!toolsSeen.isEmpty(), "模型一次都没被调用");
        assertEquals(Set.of(STRUCTURED_OUTPUT_TOOL), toolNames(toolsSeen.get(0)),
            "分类器暴露给模型的工具只能是结构化输出工具");
        assertTrue(reply != null && reply.hasStructuredData(), "空工具集下结构化输出仍须可用");
        assertEquals("refund", reply.getStructuredData(IntentResult.class).intent());
    }

    /**
     * 分类器不得落会话状态：同一会话的两次分类互不可见。
     *
     * <p>此前分类器挂着 stateStore，按 {@code intent:<会话>} 读写状态——第二次分类会把第一次的
     * 用户原文与结构化结果一并加载进上下文（上一次的判定会影响这一次），且这份状态没有任何清理入口。</p>
     */
    @Test
    void intentClassifier_shouldNotCarryStateAcrossClassifications() {
        CustomerServiceAgentFactory f = factory(new CustomerWorkProperties());
        List<List<Msg>> messagesSeen = new java.util.concurrent.CopyOnWriteArrayList<>();
        scriptStructuredIntentModel(new java.util.concurrent.CopyOnWriteArrayList<>(), messagesSeen);
        String sessionId = "intent:tenantA:conv-state";

        ReActAgent first = f.createIntentClassifierAgent(sessionId);
        first.call("第一次分类的原文MARK-1", IntentResult.class, f.contextFor(sessionId))
            .block(Duration.ofSeconds(15));
        int before = messagesSeen.size();

        f.createIntentClassifierAgent(sessionId)
            .call("第二次分类", IntentResult.class, f.contextFor(sessionId))
            .block(Duration.ofSeconds(15));

        String secondRoundContext = messagesSeen.get(before).stream()
            .map(Msg::getTextContent).collect(Collectors.joining("\n"));
        assertTrue(!secondRoundContext.contains("MARK-1"),
            "第二次分类的上下文里出现了第一次分类的原文：" + secondRoundContext);
        assertEquals(null, first.getStateStore(), "分类器不应挂状态存储");
    }

    /** 分类器同样要经过治理装配：token 计量、敏感词、注入防护等不能因为「只是分类」就裸奔。 */
    @Test
    @SuppressWarnings("unchecked")
    void intentClassifier_shouldApplyGovernanceMiddlewares() {
        AtomicInteger reasoningCalls = new AtomicInteger();
        MiddlewareBase probe = new MiddlewareBase() {
            @Override
            public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onReasoning(
                io.agentscope.core.agent.Agent agent, io.agentscope.core.agent.RuntimeContext ctx,
                io.agentscope.core.middleware.ReasoningInput input,
                java.util.function.Function<io.agentscope.core.middleware.ReasoningInput,
                    reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>> next) {
                reasoningCalls.incrementAndGet();
                return next.apply(input);
            }
        };
        var middlewares = (ObjectProvider<MiddlewareBase>) mock(ObjectProvider.class);
        Mockito.when(middlewares.orderedStream()).thenAnswer(inv -> Stream.of(probe));
        CustomerWorkProperties props = new CustomerWorkProperties();
        CustomerServiceAgentFactory f = factory(props, new KnowledgeProvider(props), middlewares);
        scriptStructuredIntentModel(new java.util.concurrent.CopyOnWriteArrayList<>(),
            new java.util.concurrent.CopyOnWriteArrayList<>());

        f.createIntentClassifierAgent("intent:gov").call("我要退款", IntentResult.class,
            f.contextFor("intent:gov")).block(Duration.ofSeconds(15));

        assertTrue(reasoningCalls.get() > 0, "分类器没有挂上治理装配器提供的中间件");
    }
}
