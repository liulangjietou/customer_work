package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.LongTermMemoryMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 长期记忆存储装配（三层记忆体系的 L2）。
 *
 * <p>按 {@code customer-work.memory.store-mode} 选择实现：<b>默认 {@code jdbc}</b>，落
 * {@link MybatisLongTermMemoryStore}（{@code cw_long_term_memory} 表，复用
 * {@code CustomerWorkPersistenceConfig} 的独立持久化环境）；显式配 {@code memory} 时用进程内实现。
 * 下游声明自己的 {@link LongTermMemoryStore} Bean 即可整体覆盖（如向量库实现）。</p>
 *
 * <p><b>降级兜底</b>：{@code jdbc} 但 {@link LongTermMemoryMapper} 取不到（宿主没有配置持久化环境）时
 * 退回进程内实现并记 error，而不是让整个容器启动失败——记忆是增强能力，缺它系统仍应能对话。
 * 这与 B2「Redis 实现失败一律降级进程内」是同一条约定。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class LongTermMemoryStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryStoreConfig.class);

    private static final String STORE_MODE_MEMORY = "memory";

    @Bean
    @ConditionalOnMissingBean(LongTermMemoryStore.class)
    public LongTermMemoryStore longTermMemoryStore(CustomerWorkProperties properties,
                                                   ObjectProvider<LongTermMemoryMapper> mapperProvider) {
        MemoryProperties cfg = properties.getMemory();
        if (STORE_MODE_MEMORY.equalsIgnoreCase(cfg.getStoreMode())) {
            log.info("long-term memory store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
            return new InMemoryLongTermMemoryStore();
        }
        LongTermMemoryMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.error("long-term memory store degraded to memory (mapper unavailable), code={}",
                "LTM-STORE-MAPPER-MISSING");
            return new InMemoryLongTermMemoryStore();
        }
        log.info("long-term memory store: jdbc (MyBatis-Plus 实现, table=cw_long_term_memory, scanLimit={})",
            cfg.getRecallScanLimit());
        return new MybatisLongTermMemoryStore(mapper, cfg.getRecallScanLimit());
    }
}
