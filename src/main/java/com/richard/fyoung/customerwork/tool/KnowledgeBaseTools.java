package com.richard.fyoung.customerwork.tool;

import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

/**
 * 知识库工具组。业务委托给可替换的 {@link KnowledgeBackend}（默认关键词 Mock，可换真实 FAQ / 向量库）。
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeBaseTools {

    private final KnowledgeBackend backend;

    public KnowledgeBaseTools(KnowledgeBackend backend) {
        this.backend = backend;
    }

    @Tool(description = "从企业知识库检索产品政策、售后规则、发票运费等常见问题答案。回答咨询类问题时优先调用，结果会带来源标注。")
    public Mono<String> searchKnowledge(
            @ToolParam(name = "query", description = "用户问题的关键描述，例如 '怎么退货' '能开发票吗'")
            String query) {
        return backend.searchKnowledge(query);
    }
}
