package com.richard.fyoung.customerwork.capability.handoff;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.handoff.mapper.HandoffMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 人机切换工单存储配置。
 *
 * <p>按 {@code human-handoff.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link MybatisHandoffStore}（MyBatis-Plus，复用 {@code CustomerWorkPersistenceConfig}
 * 的独立持久化环境），保证工单重启 / 多实例部署不丢失。{@link HandoffMapper} 用 {@link ObjectProvider}
 * 惰性获取：仅 jdbc 分支才真正取用，memory 模式下即便未装配 Mapper 也不报错。</p>
 *
 * <p>下游声明自己的 {@link HandoffStore} Bean 即可整体覆盖（如 Redis 实现）。
 * {@link HandoffService} 通过构造注入获取 {@link HandoffStore}，业务逻辑与存储解耦。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class HandoffConfig {

    private static final Logger log = LoggerFactory.getLogger(HandoffConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(HandoffStore.class)
    public HandoffStore handoffStore(CustomerWorkProperties properties, ObjectProvider<HandoffMapper> mapperProvider) {
        String mode = properties.getHumanHandoff().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("handoff store: jdbc (MyBatis-Plus 实现, table=cw_handoff_ticket)");
            return new MybatisHandoffStore(mapperProvider.getObject());
        }
        log.info("handoff store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryHandoffStore();
    }
}
