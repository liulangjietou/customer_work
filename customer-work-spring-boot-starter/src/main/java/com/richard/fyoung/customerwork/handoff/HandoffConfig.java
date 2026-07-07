package com.richard.fyoung.customerwork.handoff;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 人机切换工单存储配置。
 *
 * <p>按 {@code human-handoff.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link JdbcHandoffStore}，保证工单重启 / 多实例部署不丢失。jdbc 模式复用
 * {@code session.mysql.*} 的连接配置，与会话持久化共享同一 MySQL 实例（避免为工单单独引入一套
 * 数据库凭据配置）。</p>
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
    public HandoffStore handoffStore(CustomerWorkProperties properties) {
        String mode = properties.getHumanHandoff().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("handoff store: jdbc (mysql, table=cw_handoff_ticket)");
            return new JdbcHandoffStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("handoff store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryHandoffStore();
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-handoff-pool");
        return ds;
    }
}
