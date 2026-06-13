package com.richard.fyoung.customerwork.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库工具组（对应流程图②"RAG 知识检索"与④"咨询子 Agent"）。
 *
 * <p>这是一个最小化的"伪 RAG"：用关键词命中模拟从私有知识库召回片段，并返回带来源标注的结果，
 * 让回答可溯源、减少幻觉。生产中把方法体替换为：</p>
 * <ul>
 *   <li>调用 AgentScope 内置基于 Embedding 的 RAG 标准实现（私有化向量库），或</li>
 *   <li>通过 agentscope-extensions-rag-dify 接入 Dify，或</li>
 *   <li>接阿里云百炼企业级知识库（更强的检索与重排）。</li>
 * </ul>
 * @author owlzhangfq@gmail.com
 */
public class KnowledgeBaseTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseTools.class);

    // 模拟知识库：关键词 -> (内容, 来源)
    private static final List<Map<String, String>> KB = List.of(
        Map.of("keys", "退货,退款,七天,无理由",
               "content", "支持七天无理由退货，商品需保持完好、不影响二次销售；定制类、生鲜类除外。",
               "source", "《售后服务政策》第 3 条"),
        Map.of("keys", "发票,开票,报销",
               "content", "支持开具电子普通发票与增值税专用发票，可在订单详情页自助申请，1-3 个工作日开具。",
               "source", "《发票管理规则》第 1 条"),
        Map.of("keys", "运费,包邮,邮费",
               "content", "单笔订单满 99 元包邮，偏远地区除外；退货运费由责任方承担。",
               "source", "《运费说明》第 2 条")
    );

    @Tool(description = "从企业知识库检索产品政策、售后规则、发票运费等常见问题答案。回答咨询类问题时优先调用，结果会带来源标注。")
    public Mono<String> searchKnowledge(
            @ToolParam(name = "query", description = "用户问题的关键描述，例如 '怎么退货' '能开发票吗'")
            String query) {
        log.info("[KnowledgeBaseTools] RAG 检索: {}", query);
        return Mono.fromSupplier(() -> {
                String hits = KB.stream()
                    .filter(doc -> {
                        for (String k : doc.get("keys").split(",")) {
                            if (query.contains(k)) return true;
                        }
                        return false;
                    })
                    .map(doc -> "· " + doc.get("content") + "（来源：" + doc.get("source") + "）")
                    .collect(Collectors.joining("\n"));
                return hits.isBlank()
                    ? "未在知识库中检索到直接相关条目，建议结合上下文回答或转人工。"
                    : "知识库召回如下：\n" + hits;
            })
            .delayElement(Duration.ofMillis(110))
            .onErrorResume(e -> {
                log.error("[KnowledgeBaseTools] RAG 检索失败: {}", query, e);
                return Mono.just("知识库检索暂时不可用。");
            });
    }
}
