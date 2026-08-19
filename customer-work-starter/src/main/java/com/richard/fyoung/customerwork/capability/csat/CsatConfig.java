package com.richard.fyoung.customerwork.capability.csat;

import com.richard.fyoung.customerwork.capability.csat.mapper.CsatSurveyMapper;
import com.richard.fyoung.customerwork.core.support.OpsScopeResolver;
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
    public CsatService csatService(CsatStore store, OpsScopeResolver opsScopeResolver) {
        return new CsatService(store, opsScopeResolver);
    }

    /**
     * 工单终态 → 满意度邀请。
     *
     * <p>没有它，邀请只会在会话空闲超时清理时发出（用户早已离开），回收率的分母近乎恒为 0，
     * 看板三个指标全是 0.0%——而链路本身不报任何错。</p>
     */
    @Bean
    @ConditionalOnMissingBean(CsatTicketInviteListener.class)
    public CsatTicketInviteListener csatTicketInviteListener(CsatService csatService) {
        return new CsatTicketInviteListener(csatService);
    }
}
