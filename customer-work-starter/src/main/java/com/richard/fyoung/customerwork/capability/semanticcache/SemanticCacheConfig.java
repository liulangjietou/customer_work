package com.richard.fyoung.customerwork.capability.semanticcache;

import com.richard.fyoung.customerwork.capability.semanticcache.mapper.SemanticCacheMapper;
import com.richard.fyoung.customerwork.core.agent.MultiAgentOrchestrator;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.data.knowledge.embedding.EmbeddingClient;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(SemanticCacheStore.class)
    public SemanticCacheStore semanticCacheStore(CustomerWorkProperties properties,
                                                 ObjectProvider<SemanticCacheMapper> mapperProvider) {
        String mode = properties.getSemanticCache().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("semantic cache store: jdbc (MyBatis-Plus 实现, table=cw_semantic_cache)");
            return new MybatisSemanticCacheStore(mapperProvider.getObject());
        }
        log.info("semantic cache store: memory (进程内，多副本下命中率被实例数除掉，生产建议 store-mode=jdbc)");
        return new InMemorySemanticCacheStore();
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
