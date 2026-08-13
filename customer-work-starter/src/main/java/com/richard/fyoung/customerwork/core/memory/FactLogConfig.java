package com.richard.fyoung.customerwork.core.memory;

import com.richard.fyoung.customerwork.core.memory.mapper.FactLogMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.infra.config.properties.FactLogProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 事实日志装配（三层记忆体系的 L3）。
 *
 * <p>按 {@code customer-work.fact-log.store-mode} 选择实现：<b>默认 {@code jdbc}</b>，落
 * {@link MybatisFactLog}（{@code cw_fact_log} 表）；显式配 {@code file} 时用落盘 JSONL 实现
 * {@link FileFactLog}。下游声明自己的 {@link FactLog} Bean 即可整体覆盖。</p>
 *
 * <p><b>降级兜底</b>：{@code jdbc} 但 {@link FactLogMapper} 取不到（宿主没有配置持久化环境）时
 * 退回落盘实现并记 error——事实日志宁可写在本地盘也不要整条丢掉，更不该因此让容器起不来。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class FactLogConfig {

    private static final Logger log = LoggerFactory.getLogger(FactLogConfig.class);

    private static final String STORE_MODE_FILE = "file";

    @Bean
    @ConditionalOnMissingBean(FactLog.class)
    public FactLog factLog(CustomerWorkProperties properties, ObjectProvider<FactLogMapper> mapperProvider) {
        FactLogProperties cfg = properties.getFactLog();
        if (STORE_MODE_FILE.equalsIgnoreCase(cfg.getStoreMode())) {
            log.info("fact log store: file (dir={}, maxFileMb={})", cfg.getDirectory(), cfg.getMaxFileMb());
            return newFileFactLog(cfg);
        }
        FactLogMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.error("fact log store degraded to file (mapper unavailable), code={}, dir={}",
                "FACT-LOG-MAPPER-MISSING", cfg.getDirectory());
            return newFileFactLog(cfg);
        }
        log.info("fact log store: jdbc (MyBatis-Plus 实现, table=cw_fact_log, readLimit={})", cfg.getReadLimit());
        return new MybatisFactLog(mapper, cfg.isEnabled(), cfg.getReadLimit());
    }

    private static FileFactLog newFileFactLog(FactLogProperties cfg) {
        return new FileFactLog(cfg.isEnabled(), Path.of(cfg.getDirectory()),
            cfg.getMaxFileMb(), cfg.getMaxArchivedFiles());
    }
}
