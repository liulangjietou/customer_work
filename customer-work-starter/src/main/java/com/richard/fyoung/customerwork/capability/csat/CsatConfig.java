package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.capability.csat.mapper.CsatSurveyMapper;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CSAT 装配。
 *
 * <p>按 {@code csat.store-mode} 选择存储；默认 memory。下游声明自己的 {@link CsatStore} Bean 即可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class CsatConfig {

    private static final Logger log = LoggerFactory.getLogger(CsatConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(CsatStore.class)
    public CsatStore csatStore(CustomerWorkProperties properties,
                               ObjectProvider<CsatSurveyMapper> mapperProvider) {
        String mode = properties.getCsat().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("csat store: jdbc (MyBatis-Plus 实现, table=cw_csat_survey)");
            return new MybatisCsatStore(mapperProvider.getObject());
        }
        log.info("csat store: memory (进程内，重启清空，趋势无从谈起，生产建议 store-mode=jdbc)");
        return new InMemoryCsatStore();
    }

    @Bean
    @ConditionalOnMissingBean(CsatService.class)
    public CsatService csatService(CsatStore store, TenantResolver tenantResolver) {
        return new CsatService(store, tenantResolver);
    }
}
