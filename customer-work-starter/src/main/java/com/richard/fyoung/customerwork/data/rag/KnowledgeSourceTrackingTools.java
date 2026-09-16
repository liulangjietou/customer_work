package com.richard.fyoung.customerwork.data.rag;

import com.richard.fyoung.customerwork.core.service.ChatTerminalCapture;
import com.richard.fyoung.customerwork.safety.security.AgentInvocationIdentity;
import com.richard.fyoung.customerwork.safety.tenant.TenantContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.KnowledgeRetrievalTools;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.util.List;
import reactor.core.publisher.Mono;

/** 在框架格式化正文前记录实际元数据；按本次原生上下文组合，不依赖阻塞订阅传播 Reactor Context。 */
public final class KnowledgeSourceTrackingTools {

    public static final String TOOL_NAME = "retrieve_knowledge";
    private final Knowledge knowledge;

    private KnowledgeSourceTrackingTools(Knowledge knowledge) {
        this.knowledge = knowledge;
    }

    /** 构建器完成 RAG 装配后替换同名工具，保留框架提示词、参数与格式化行为。 */
    public static void install(Toolkit toolkit, Knowledge knowledge) {
        toolkit.removeTool(TOOL_NAME);
        toolkit.registerTool(new KnowledgeSourceTrackingTools(knowledge));
    }

    /** 委托框架进行参数解释与格式化，仅捕获这一次检索返回的 Document。 */
    @Tool(name = TOOL_NAME, description = "Retrieve relevant documents from knowledge base. Use this tool when you need to find specific information or when user asks questions about stored knowledge.")
    public String retrieveKnowledge(
        @ToolParam(name = "query", description = "The search query to find relevant documents in the knowledge base")
        String query,
        @ToolParam(name = "limit", description = "Maximum number of documents to retrieve (default: 5)", required = false)
        Integer limit, Agent agent, RuntimeContext context) {
        ChatTerminalCapture capture = context == null ? null : context.get(ChatTerminalCapture.class);
        Knowledge source = capture == null ? knowledge : new Knowledge() {
            @Override
            public Mono<Void> addDocuments(List<Document> documents) {
                return knowledge.addDocuments(documents);
            }

            @Override
            public Mono<List<Document>> retrieve(String text, RetrieveConfig config) {
                return knowledge.retrieve(text, config).doOnNext(capture::acceptKnowledgeDocuments);
            }
        };
        // 原生调用快照跨越模型与工具调度；不得把执行线程残留的租户当成本轮身份。
        AgentInvocationIdentity identity = context == null ? null : context.get(AgentInvocationIdentity.class);
        String tenantId = identity == null ? TenantContext.get() : identity.tenantId();
        return TenantContext.callWith(tenantId,
            () -> new KnowledgeRetrievalTools(source).retrieveKnowledge(query, limit, agent, context));
    }
}
