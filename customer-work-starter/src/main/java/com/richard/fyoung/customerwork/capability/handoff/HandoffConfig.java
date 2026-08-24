package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 历史人机切换表的迁移测试配置。
 *
 * <p>P1-03 起生产 HandoffService 统一使用 TicketService/cw_ticket，本类不再是 Spring Configuration，
 * 因而不会注册第二个 HandoffStore 权威源。保留工厂方法仅用于旧表迁移回归测试。</p>
 * @author owlzhangfq@gmail.com
 */
public class HandoffConfig {

    private static final Logger log = LoggerFactory.getLogger(HandoffConfig.class);

    public HandoffStore handoffStore(CustomerWorkProperties properties, ObjectProvider<HandoffMapper> mapperProvider) {
        String mode = properties.getHumanHandoff().getStoreMode();
        if (StoreModes.isJdbc(mode)) {
            log.info("legacy handoff migration store: jdbc, table=cw_handoff_ticket");
            return new MybatisHandoffStore(mapperProvider.getObject());
        }
        log.info("legacy handoff migration store: memory");
        return new InMemoryHandoffStore();
    }
}
