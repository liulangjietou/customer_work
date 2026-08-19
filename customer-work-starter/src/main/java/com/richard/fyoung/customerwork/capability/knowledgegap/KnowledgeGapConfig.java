package com.richard.fyoung.customerwork.capability.knowledgegap;

import com.richard.fyoung.customerwork.capability.knowledgegap.mapper.KnowledgeGapMapper;
import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识盲区分析装配。
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class KnowledgeGapConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGapConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(KnowledgeGapStore.class)
    public KnowledgeGapStore knowledgeGapStore(CustomerWorkProperties properties,
                                               ObjectProvider<KnowledgeGapMapper> mapperProvider) {
        String mode = properties.getKnowledgeGap().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("knowledge gap store: jdbc (MyBatis-Plus 实现, table=cw_knowledge_gap)");
            return new MybatisKnowledgeGapStore(mapperProvider.getObject());
        }
        log.info("knowledge gap store: memory (进程内，重启清零，看不出'反复'，生产建议 store-mode=jdbc)");
        return new InMemoryKnowledgeGapStore();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeGapService.class)
    public KnowledgeGapService knowledgeGapService(KnowledgeGapStore store,
                                                   OpsScopeResolver opsScopeResolver,
                                                   CustomerWorkProperties properties) {
        return new KnowledgeGapService(store, opsScopeResolver, properties.getKnowledgeGap());
    }
}
