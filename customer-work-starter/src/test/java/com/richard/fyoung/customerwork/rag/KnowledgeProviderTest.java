package com.richard.fyoung.customerwork.rag;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void warmUp_shouldPopulateCache_soSubsequentGetNeverCallsBuildAgain() {
        // 复现场景：WebFlux 应用（如 customer-work-downstream-app）把本 starter 当依赖引入时，
        // 第一次真实请求触发 get() 的懒加载若落在 reactor-http-nio 事件循环线程上，build() 里的
        // .block() 会直接抛 IllegalStateException（Reactor 3.4+ 禁止在非阻塞线程上 block）。
        // Spring 容器会在应用启动阶段（普通主线程，不受 Reactor 调度管辖）调用 @PostConstruct
        // 方法，此处直接模拟这一调用，验证预热后缓存已经就绪、get() 不会再触碰 build()。
        KnowledgeProvider provider = new KnowledgeProvider(new CustomerWorkProperties());

        provider.warmUp();
        Knowledge warmed = provider.get();

        assertSame(warmed, provider.get(), "预热后 get() 应直接返回缓存实例，不重新 build()");
    }

    @Test
    void get_shouldBuildSimpleVectorKnowledge_whenProviderSimple() {
        // 真实 Embedding 向量 RAG：构造离线（嵌入调用发生在 retrieve/add 时），仅校验装配
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getModel().setApiKey("sk-test");
        props.getRag().setProvider("simple");

        assertInstanceOf(SimpleKnowledge.class, new KnowledgeProvider(props).get());
    }

    @Test
    void get_shouldBuildDifyKnowledge_whenProviderDify() {
        CustomerWorkProperties props = new CustomerWorkProperties();
        props.getRag().setProvider("dify");
        props.getRag().getDify().setApiKey("dify-test");
        props.getRag().getDify().setApiBaseUrl("http://localhost:8080/v1");
        props.getRag().getDify().setDatasetId("ds-1");

        assertInstanceOf(io.agentscope.core.rag.integration.dify.DifyKnowledge.class,
            new KnowledgeProvider(props).get());
    }
}
