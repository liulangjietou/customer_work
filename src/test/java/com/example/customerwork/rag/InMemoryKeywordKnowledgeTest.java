package com.example.customerwork.rag;

import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 知识库单测（特性「RAG」）：灌库与按相关度召回。
 * @author owlzhangfq@gmail.com
 */
class InMemoryKeywordKnowledgeTest {

    @Test
    void retrieve_shouldReturnRelevantDocument() {
        InMemoryKeywordKnowledge knowledge = new InMemoryKeywordKnowledge(3);
        knowledge.addTexts(List.of(
            "支持七天无理由退货，定制类、生鲜类除外。",
            "单笔订单满 99 元包邮，偏远地区除外。")).block();

        StepVerifier.create(knowledge.retrieve("七天无理由退货政策", RetrieveConfig.builder().limit(3).build()))
            .assertNext(docs -> {
                assertTrue(!docs.isEmpty(), "应召回文档");
                String top = docs.get(0).getMetadata().getContentText();
                assertTrue(top.contains("七天无理由"), "最相关文档应是退货政策，实际: " + top);
            })
            .verifyComplete();
    }

    @Test
    void retrieve_shouldRespectLimit() {
        InMemoryKeywordKnowledge knowledge = new InMemoryKeywordKnowledge(5);
        knowledge.addTexts(List.of("退货政策", "发票规则", "运费说明", "资金安全")).block();

        StepVerifier.create(knowledge.retrieve("退货 发票 运费 资金", RetrieveConfig.builder().limit(2).build()))
            .assertNext(docs -> assertTrue(docs.size() <= 2, "应遵守 limit=2，实际 " + docs.size()))
            .verifyComplete();
    }

    @Test
    void retrieve_shouldReturnEmpty_forNoMatchOrEmptyStore() {
        InMemoryKeywordKnowledge empty = new InMemoryKeywordKnowledge(3);
        StepVerifier.create(empty.retrieve("任意", RetrieveConfig.builder().build()))
            .assertNext(docs -> assertTrue(docs.isEmpty()))
            .verifyComplete();
    }
}
