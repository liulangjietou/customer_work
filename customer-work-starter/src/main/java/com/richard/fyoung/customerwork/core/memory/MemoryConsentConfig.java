package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.core.memory.mapper.MemoryConsentMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 长期记忆同意存储装配。 */
@Configuration
public class MemoryConsentConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsentConfig.class);

    @Bean
    @ConditionalOnMissingBean(MemoryConsentStore.class)
    public MemoryConsentStore memoryConsentStore(CustomerWorkProperties properties,
                                                  ObjectProvider<MemoryConsentMapper> mapperProvider) {
        if (StoreModes.isMemory(properties.getMemory().getConsentStoreMode())) {
            log.info("memory consent store: memory (development only)");
            return new InMemoryMemoryConsentStore();
        }
        MemoryConsentMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            if (properties.getMemory().isConsentRequired()) {
                throw new IllegalStateException("memory consent requires jdbc mapper");
            }
            log.error("memory consent store degraded to memory, code={}", "MEMORY-CONSENT-MAPPER-MISSING");
            return new InMemoryMemoryConsentStore();
        }
        log.info("memory consent store: jdbc (table=cw_memory_consent)");
        return new MybatisMemoryConsentStore(mapper);
    }
}
