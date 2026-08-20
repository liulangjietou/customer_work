package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.core.memory.mapper.HarnessMemoryMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Harness 分层记忆权威存储装配。
 *
 * <p>按 {@code customer-work.harness.memory-store-mode} 选择实现：<b>默认 {@code jdbc}</b>，落
 * {@link MybatisHarnessMemoryStore}（{@code cw_harness_memory} 表）；显式配 {@code memory} 时用进程内实现。
 * Mapper 取不到时降级进程内并记 error——记忆是增强能力，缺它不该让容器起不来。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class HarnessMemoryStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessMemoryStoreConfig.class);

    @Bean
    @ConditionalOnMissingBean(HarnessMemoryStore.class)
    public HarnessMemoryStore harnessMemoryStore(CustomerWorkProperties properties,
                                                  ObjectProvider<HarnessMemoryMapper> mapperProvider) {
        if (StoreModes.isMemory(properties.getHarness().getMemoryStoreMode())) {
            log.info("harness memory store: memory (进程内，重启不保留，生产建议 memory-store-mode=jdbc)");
            return new InMemoryHarnessMemoryStore();
        }
        HarnessMemoryMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.error("harness memory store degraded to memory (mapper unavailable), code={}",
                "HARNESS-MEMORY-MAPPER-MISSING");
            return new InMemoryHarnessMemoryStore();
        }
        log.info("harness memory store: jdbc (MyBatis-Plus 实现, table=cw_harness_memory)");
        return new MybatisHarnessMemoryStore(mapper);
    }
}
