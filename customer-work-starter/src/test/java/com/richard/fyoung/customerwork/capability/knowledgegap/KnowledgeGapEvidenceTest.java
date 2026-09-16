package com.richard.fyoung.customerwork.capability.knowledgegap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
import com.richard.fyoung.customerwork.data.calllog.AgentCallMeta;
import com.richard.fyoung.customerwork.data.calllog.AgentCallSessionType;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeInjectionMiddleware;
import com.richard.fyoung.customerwork.infra.config.properties.KnowledgeGapProperties;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.subjectquota.QuotaSubjectType;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import com.richard.fyoung.customerwork.tool.KnowledgeBaseTools;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 在实际工具注入与异步检索中验证来源，避免只测手工传参而漏掉运行链路。 */
class KnowledgeGapEvidenceTest {
    private final InMemoryKnowledgeGapStore store = new InMemoryKnowledgeGapStore();
    private final KnowledgeGapService service = new KnowledgeGapService(
        store, new OpsScopeResolver(), enabled());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void toolUsesInjectedInvocationAfterThreadSwitchAndKeepsSchemaClean() {
        var toolkit = new Toolkit();
        var observedQuery = new java.util.concurrent.atomic.AtomicReference<String>();
        toolkit.registerTool(new KnowledgeBaseTools(query -> {
            observedQuery.set(query);
            return Mono.just(KnowledgeBackend.NO_HIT_REPLY).delayElement(Duration.ofMillis(10));
        }, service));
        var call = ToolUseBlock.builder().id("search-1").name("searchKnowledge")
            .input(Map.of("query", "如何申请电子发票"))
            .content("{\"query\":\"如何申请电子发票\"}").build();
        var result = toolkit.callTool(ToolCallParam.builder().toolUseBlock(call).input(call.getInput()).runtimeContext(context()).build())
            .subscribeOn(Schedulers.boundedElastic()).block(Duration.ofSeconds(5));
        assertEquals("如何申请电子发票", observedQuery.get(), "先证明真实工具确实执行并取得查询参数: " + result);
        assertEvidence("TOOL");
        var schema = toolkit.getToolSchemas().get(0).getParameters();
        assertEquals(java.util.Set.of("query"), ((Map<?, ?>) schema.get("properties")).keySet(),
            "运行上下文和来源身份均不得成为模型参数");
    }

    @Test
    void injectionRecordsTheExecutingAgentAndChannelOnlyOncePerTurn() {
        var middleware = new KnowledgeInjectionMiddleware((agent, query) -> null,
            "invoice-agent", service);
        var input = new ReasoningInput(List.of(Msg.builder().role(MsgRole.USER)
            .content(TextBlock.builder().text("如何申请电子发票").build()).build()), List.of(), null);
        var context = context();
        middleware.onReasoning(null, context, input, next -> Flux.empty()).blockLast();
        middleware.onReasoning(null, context, input, next -> Flux.empty()).blockLast();
        assertEvidence("INJECTION");
        assertEquals(1, store.topGaps("tenant-a", 50).get(0).missCount());
    }

    private void assertEvidence(String path) {
        var gaps = store.topGaps("tenant-a", 50);
        assertEquals(1, gaps.size(), "异步记录应回到可信调用租户，不能写入 default");
        var evidence = gaps.get(0).evidence();
        assertEquals(path, evidence.path().name());
        assertEquals("invoice-agent", evidence.agentCode());
        assertEquals("user-ws", evidence.channelCode());
        assertEquals("CHAT", evidence.sessionType());
        assertEquals("EMPTY", evidence.retrievalResult().name());
        assertTrue(store.topGaps("default", 50).isEmpty());
    }

    private RuntimeContext context() {
        var context = RuntimeContext.builder().sessionId("conversation-1").build();
        context.put(AgentInvocationIdentity.class,
            new AgentInvocationIdentity("tenant-a", QuotaSubjectType.USER, "42", true)
                .forInvocation(AgentInvocationIdentity.CHANNEL_USER_WS, "conversation-1", "invoice-agent"));
        context.put(AgentCallMeta.class, new AgentCallMeta("request-1", "tester", "invoice-agent",
            "发票助手", AgentCallSessionType.CHAT, "如何申请电子发票"));
        return context;
    }

    private static KnowledgeGapProperties enabled() {
        var properties = new KnowledgeGapProperties();
        properties.setEnabled(true);
        return properties;
    }
}
