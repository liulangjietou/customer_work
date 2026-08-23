package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.RuntimeConfigCacheInvalidator;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * 语义缓存装配门控测试。
 *
 * <p><b>为什么单独写这个类</b>：{@code SemanticCacheServiceTest} 全程注入 mock 的
 * {@link EmbeddingClient}，因此即使容器里根本没有这个 Bean，那 21 条用例照样全绿——
 * 而没有 Bean 时缓存会静默失效（{@code getIfAvailable()} 恒为 null），开了开关也毫无效果。
 * starter 一度确实没有装配过 {@code EmbeddingClient}（只有单测直接 new 过），
 * 这个洞正是"只测逻辑、不测装配"漏掉的。</p>
 *
 * <p>所以这里断言的是<b>容器里到底有没有那个 Bean</b>，而不是业务行为。</p>
 * @author owlzhangfq@gmail.com
 */
class SemanticCacheConfigTest {

    /** 语义缓存的协作者：编排器与租户解析，测试里给最小可用替身。 */
    @Configuration
    @EnableConfigurationProperties(CustomerWorkProperties.class)
    static class Collaborators {

        @Bean
        MultiAgentOrchestrator multiAgentOrchestrator(CustomerWorkProperties properties) {
            return new MultiAgentOrchestrator(mock(Model.class), properties,
                new com.richard.fyoung.customerwork.tool.backend.MockOrderBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockAfterSalesBackend(),
                new com.richard.fyoung.customerwork.tool.backend.MockKnowledgeBackend());
        }

        @Bean
        TenantResolver tenantResolver(CustomerWorkProperties properties) {
            return new TenantResolver(properties);
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class, SemanticCacheConfig.class);
    }

    @Test
    void enabled_shouldProvideEmbeddingClient() {
        runner()
            .withPropertyValues("customer-work.semantic-cache.enabled=true")
            .run(context -> {
                assertEquals(1, context.getBeanNamesForType(EmbeddingClient.class).length,
                    "开启语义缓存必须同时装配 EmbeddingClient，否则功能静默失效");
                assertNotNull(context.getBean(SemanticCacheService.class));
            });
    }

    @Test
    void disabled_shouldNotBuildEmbeddingClient() {
        runner()
            .withPropertyValues("customer-work.semantic-cache.enabled=false")
            .run(context -> assertEquals(0, context.getBeanNamesForType(EmbeddingClient.class).length,
                "没开这个功能却建一个连不通的 HTTP 客户端毫无意义"));
    }

    @Test
    void defaultConfig_shouldBeDisabled() {
        // 默认关闭是刻意的：无差别缓存客服回答会造成数据泄露，必须显式开启
        runner().run(context -> assertEquals(0, context.getBeanNamesForType(EmbeddingClient.class).length));
    }

    @Test
    void customEmbeddingClient_shouldWin() {
        EmbeddingClient custom = mock(EmbeddingClient.class);
        runner()
            .withPropertyValues("customer-work.semantic-cache.enabled=true")
            .withBean(EmbeddingClient.class, () -> custom)
            .run(context -> assertEquals(custom, context.getBean(EmbeddingClient.class),
                "下游换向量源（自建模型/其他厂商）应能整体覆盖"));
    }

    @Test
    void defaultStore_shouldBeInMemory() {
        runner().run(context -> {
            assertEquals(InMemorySemanticCacheStore.class, context.getBean(SemanticCacheStore.class).getClass());
            assertSame(context.getBean(SemanticCacheService.class),
                context.getBean(RuntimeConfigCacheInvalidator.class),
                "Nacos 热更新必须能从容器解析到语义缓存失效边界");
        });
    }
}
