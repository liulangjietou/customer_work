package com.richard.fyoung.customerwork.user;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 用户账户域装配：按 {@code customer-work.user-auth.store-mode} 选择存储实现并装配服务。
 *
 * <p>结构对齐 {@code HandoffConfig}：默认 {@code memory}；{@code jdbc} 落地为 {@link JdbcUserAccountStore}，
 * 复用 {@code session.mysql.*} 连接配置。两个 Bean 均 {@code @ConditionalOnMissingBean}，下游可覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class UserAccountConfig {

    private static final Logger log = LoggerFactory.getLogger(UserAccountConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(UserAccountStore.class)
    public UserAccountStore userAccountStore(CustomerWorkProperties properties) {
        String mode = properties.getUserAuth().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("user store: jdbc (mysql, table=cw_user)");
            return new JdbcUserAccountStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("user store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryUserAccountStore();
    }

    @Bean
    @ConditionalOnMissingBean(UserAccountService.class)
    public UserAccountService userAccountService(UserAccountStore userAccountStore) {
        return new UserAccountService(userAccountStore);
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-user-pool");
        return ds;
    }
}
