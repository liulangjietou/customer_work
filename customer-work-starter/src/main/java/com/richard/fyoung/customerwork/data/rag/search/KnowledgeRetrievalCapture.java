package com.richard.fyoung.customerwork.data.rag.search;

import io.agentscope.core.agent.RuntimeContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单次调用的来源采集器，只挂在本轮上下文；不随共享 Agent 或知识库实例复用。 */
public final class KnowledgeRetrievalCapture {

    private final Map<String, Retrieval> retrievals = new LinkedHashMap<>();

    /** 调用入口显式开启来源采集，未开启的宿主保持原有行为。 */
    public static void bind(RuntimeContext context, KnowledgeRetrievalCapture capture) {
        context.put(KnowledgeRetrievalCapture.class, capture);
    }

    /** 中间件仅向本轮已有的采集器提交一次实际结果。 */
    public static void record(RuntimeContext context, String agentCode, KnowledgeRetrievalResult result) {
        KnowledgeRetrievalCapture capture = context == null ? null : context.get(KnowledgeRetrievalCapture.class);
        if (capture != null) {
            capture.record(agentCode, result);
        }
    }

    /** 子智能体可并发共享上下文，各自第一次检索结果保持独立。 */
    public synchronized void record(String agentCode, KnowledgeRetrievalResult result) {
        retrievals.putIfAbsent(agentCode, new Retrieval(agentCode, result.status(), result.sources()));
    }

    /** 调用结束时冻结仅含状态与来源的快照，禁止携带检索正文。 */
    public synchronized List<Retrieval> snapshot() {
        return List.copyOf(retrievals.values());
    }

    public record Retrieval(String agentCode, KnowledgeRetrievalResult.Status status,
                            List<KnowledgeRetrievalSource> sources) {
        public Retrieval {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
