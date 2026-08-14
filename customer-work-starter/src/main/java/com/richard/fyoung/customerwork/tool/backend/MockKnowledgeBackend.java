package com.richard.fyoung.customerwork.tool.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库后端的默认演示实现与单元测试（关键词命中）。生产可改为调用真实 FAQ / 向量库。
 * @author owlzhangfq@gmail.com
 */
public class MockKnowledgeBackend implements KnowledgeBackend {

    private static final Logger log = LoggerFactory.getLogger(MockKnowledgeBackend.class);

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

    @Override
    public Mono<String> searchKnowledge(String query) {
        log.info("[MockKnowledgeBackend] 检索: {}", query);
        return Mono.fromSupplier(() -> {
                String hits = KB.stream()
                    .filter(doc -> {
                        for (String k : doc.get("keys").split(",")) {
                            if (query.contains(k)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .map(doc -> "· " + doc.get("content") + "（来源：" + doc.get("source") + "）")
                    .collect(Collectors.joining("\n"));
                return hits.isBlank()
                    ? NO_HIT_REPLY
                    : "知识库召回如下：\n" + hits;
            })
            .delayElement(Duration.ofMillis(110))
            .onErrorResume(e -> Mono.just("知识库检索暂时不可用。"));
    }
}
