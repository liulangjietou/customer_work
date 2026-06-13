package com.richard.fyoung.customerwork.rag;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库提供方单测：默认 memory 实现灌入预置文档并可召回，且实例为单例复用。
 * （bailian 实现需真实百炼凭据，由集成环境验证；此处只覆盖默认实现与选择逻辑。）
 * @author owlzhangfq@gmail.com
 */
class KnowledgeProviderTest {

    @Test
    void get_shouldReturnSeededMemoryKnowledge_byDefault() {
        KnowledgeProvider provider = new KnowledgeProvider(new CustomerWorkProperties());
        Knowledge knowledge = provider.get();

        StepVerifier.create(knowledge.retrieve("七天无理由退货", RetrieveConfig.builder().limit(3).build()))
            .assertNext(docs -> {
                assertTrue(!docs.isEmpty(), "默认知识库应能召回预置文档");
                assertTrue(docs.get(0).getMetadata().getContentText().contains("七天无理由"));
            })
            .verifyComplete();
    }

    @Test
    void get_shouldReturnSameInstance() {
        KnowledgeProvider provider = new KnowledgeProvider(new CustomerWorkProperties());
        assertSame(provider.get(), provider.get(), "应复用同一共享知识库实例");
    }
}
