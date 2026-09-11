package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapService;
import com.richard.fyoung.customerwork.data.rag.search.KnowledgeGapEvidence;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 知识库工具组。业务委托给可替换的 {@link KnowledgeBackend}（默认关键词 Mock，可换真实 FAQ / 向量库）。
 *
 * <p>顺带承担<b>知识盲区埋点</b>：检索未命中时记一笔，攒出"哪些问题反复查不到"的排行。
 * 这份数据本来唾手可得，此前没人记，于是补知识全靠拍脑袋。</p>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeBaseTools {

    private final KnowledgeBackend backend;

    /** 可空：未装配盲区分析时工具行为与从前完全一致。 */
    private final KnowledgeGapService knowledgeGapService;

    public KnowledgeBaseTools(KnowledgeBackend backend) {
        this(backend, null);
    }

    public KnowledgeBaseTools(KnowledgeBackend backend, KnowledgeGapService knowledgeGapService) {
        this.backend = backend;
        this.knowledgeGapService = knowledgeGapService;
    }

    /** 兼容直接 Java 调用；无运行上下文时不推测来源。 */
    public Mono<String> searchKnowledge(String query) {
        return searchKnowledge(query, null);
    }

    /** 检索结果保持原契约，未命中统计使用框架注入的可信来源。 */
    @Tool(description = "从企业知识库检索产品政策、售后规则、发票运费等常见问题答案。回答咨询类问题时优先调用，结果会带来源标注。")
    public Mono<String> searchKnowledge(
            @ToolParam(name = "query", description = "用户问题的关键描述，例如 '怎么退货' '能开发票吗'")
            String query, RuntimeContext context) {
        Runnable record = knowledgeGapService == null ? null : knowledgeGapService.captureMiss(
            query, context, KnowledgeGapEvidence.Path.TOOL, null);
        return backend.searchKnowledge(query)
            .doOnNext(result -> {
                if (record != null && KnowledgeBackend.isMiss(result)) record.run();
            });
    }

}
