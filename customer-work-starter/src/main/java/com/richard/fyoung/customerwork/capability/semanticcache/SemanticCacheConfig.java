package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.embedding.DashScopeEmbeddingClient;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.ChatModelFactory;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.SemanticCacheProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语义缓存装配。
 *
 * <p>按 {@code semantic-cache.store-mode} 选择存储：默认 {@code memory}；
 * {@code jdbc} 落 {@code cw_semantic_cache}，多副本共享同一份缓存。</p>
 *
 * <p>{@link EmbeddingClient} 在 {@code @Bean} 方法体内用 {@link ObjectProvider} 解析：
 * 它属于 data 域，与本 capability 域之间没有可靠的装配先后，用 {@code @ConditionalOnBean}
 * 会因判定时机过早而随机失效；方法体执行时全部 Bean 定义都已注册，查找结果是确定的。
 * 取不到就传 null，缓存自动静默失效——没有向量本就谈不上语义命中。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SemanticCacheConfig {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheConfig.class);

    @Bean
    @ConditionalOnMissingBean(SemanticCacheStore.class)
    public SemanticCacheStore semanticCacheStore(CustomerWorkProperties properties,
                                                 ObjectProvider<SemanticCacheMapper> mapperProvider) {
        String mode = properties.getSemanticCache().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("semantic cache store: jdbc (MyBatis-Plus 实现, table=cw_semantic_cache)");
            return new MybatisSemanticCacheStore(mapperProvider.getObject());
        }
        log.info("semantic cache store: memory (进程内，多副本下命中率被实例数除掉，生产建议 store-mode=jdbc)");
        return new InMemorySemanticCacheStore();
    }

    /**
     * Embedding 客户端的默认装配。
     *
     * <p>starter 此前<b>没有任何地方装配过</b> {@link EmbeddingClient}——{@code DashScopeEmbeddingClient}
     * 只被单测直接 new 过。没有它语义缓存永远静默失效（{@code getIfAvailable()} 恒为 null），
     * 开了开关也不会有任何效果，只在启动时留一行 error。</p>
     *
     * <p>只在缓存开启时装配：没开这个功能却建一个连不通的 HTTP 客户端毫无意义。
     * API Key 用 {@code Supplier} 每次现取而非启动时固化，配合 Nacos 热更新与密钥轮换。
     * 下游要换向量源（自建模型、其他厂商），声明自己的 {@link EmbeddingClient} Bean 即可覆盖。</p>
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    @ConditionalOnProperty(prefix = "customer-work.semantic-cache", name = "enabled", havingValue = "true")
    public EmbeddingClient semanticCacheEmbeddingClient(CustomerWorkProperties properties) {
        SemanticCacheProperties cache = properties.getSemanticCache();
        String embeddingModel = properties.getModel().getEmbeddingName();
        log.info("semantic cache embedding client: dashscope({}), dimensions={}",
            embeddingModel, cache.getEmbeddingDimensions());
        return new DashScopeEmbeddingClient(
            () -> ChatModelFactory.resolveDashScopeKey(properties.getModel().getApiKey()),
            cache.getEmbeddingBaseUrl(),
            embeddingModel,
            cache.getEmbeddingDimensions(),
            cache.getEmbeddingBatchSize());
    }

    @Bean
    @ConditionalOnMissingBean(SemanticCacheService.class)
    public SemanticCacheService semanticCacheService(SemanticCacheStore store,
                                                     ObjectProvider<EmbeddingClient> embeddingProvider,
                                                     MultiAgentOrchestrator orchestrator,
                                                     TenantResolver tenantResolver,
                                                     CustomerWorkProperties properties) {
        EmbeddingClient embeddingClient = embeddingProvider.getIfAvailable();
        if (properties.getSemanticCache().isEnabled() && embeddingClient == null) {
            log.error("semantic cache enabled but no EmbeddingClient available, errorCode={}",
                "SEMCACHE-NO-EMBEDDING");
        }
        return new SemanticCacheService(store, embeddingClient, orchestrator, tenantResolver,
            properties.getSemanticCache());
    }
}
