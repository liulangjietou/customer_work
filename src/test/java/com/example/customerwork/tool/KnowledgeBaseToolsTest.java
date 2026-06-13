package com.example.customerwork.tool;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库工具组单测：命中返回带来源标注的内容，未命中给出兜底提示。
 * @author owlzhangfq@gmail.com
 */
class KnowledgeBaseToolsTest {

    private final KnowledgeBaseTools tools = new KnowledgeBaseTools();

    @Test
    void searchKnowledge_shouldHitRefundPolicy_withSource() {
        StepVerifier.create(tools.searchKnowledge("怎么退货"))
            .assertNext(result -> {
                assertTrue(result.contains("七天无理由"), "应召回退货政策");
                assertTrue(result.contains("来源"), "结果应带来源标注，便于溯源");
            })
            .verifyComplete();
    }

    @Test
    void searchKnowledge_shouldReturnFallback_whenNoHit() {
        StepVerifier.create(tools.searchKnowledge("完全不相关的火星天气"))
            .assertNext(result -> assertTrue(result.contains("未在知识库中检索到")))
            .verifyComplete();
    }
}
