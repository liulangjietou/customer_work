package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.capability.knowledgegap.KnowledgeGapService;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
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

    @Tool(description = "从企业知识库检索产品政策、售后规则、发票运费等常见问题答案。回答咨询类问题时优先调用，结果会带来源标注。")
    public Mono<String> searchKnowledge(
            @ToolParam(name = "query", description = "用户问题的关键描述，例如 '怎么退货' '能开发票吗'")
            String query) {
        return backend.searchKnowledge(query)
            .doOnNext(result -> recordGapIfMiss(query, result));
    }

    /**
     * 未命中即记一笔。
     *
     * <p>判定走 {@link KnowledgeBackend#isMiss}——未命中文案是接口契约的一部分，不是这里自己认的字符串；
     * 后端改文案时会连带改常量，埋点不会静默失效。</p>
     *
     * <p>工具层拿不到 sessionId，故分区键传空走默认分区。盲区排行是<b>全局</b>视角的运营数据
     * （"这批用户在问什么我们答不上来"），本就不需要按会话细分。</p>
     */
    private void recordGapIfMiss(String query, String result) {
        if (knowledgeGapService == null || !KnowledgeBackend.isMiss(result)) {
            return;
        }
        knowledgeGapService.recordMiss(null, query);
    }
}
