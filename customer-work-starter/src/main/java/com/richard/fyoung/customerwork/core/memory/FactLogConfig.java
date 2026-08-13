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

/**
 * 事实日志装配（三层记忆体系的 L3）：落 {@link MybatisFactLog}（{@code cw_fact_log} 表）。
 *
 * <p>只有落库一种形态，不再提供文件实现——多副本各写各的、容器销毁即丢，那种事实日志没有审计价值。
 * 下游声明自己的 {@link FactLog} Bean 即可整体覆盖。</p>
 *
 * <p><b>兜底</b>：{@link FactLogMapper} 取不到（宿主没有配置持久化环境）时装 {@link NoOpFactLog} 并记 error，
 * 而不是让容器起不来，也不是偷偷写到本地盘——事实日志是旁路能力，缺它不该打断对话主链路。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class FactLogConfig {

    private static final Logger log = LoggerFactory.getLogger(FactLogConfig.class);

    @Bean
    @ConditionalOnMissingBean(FactLog.class)
    public FactLog factLog(CustomerWorkProperties properties, ObjectProvider<FactLogMapper> mapperProvider) {
        FactLogProperties cfg = properties.getFactLog();
        FactLogMapper mapper = mapperProvider.getIfAvailable();
        if (mapper == null) {
            log.error("fact log disabled (mapper unavailable, 事实日志本批次不再落盘), code={}",
                "FACT-LOG-MAPPER-MISSING");
            return new NoOpFactLog();
        }
        log.info("fact log store: jdbc (MyBatis-Plus 实现, table=cw_fact_log, readLimit={})", cfg.getReadLimit());
        return new MybatisFactLog(mapper, cfg.isEnabled(), cfg.getReadLimit());
    }
}
