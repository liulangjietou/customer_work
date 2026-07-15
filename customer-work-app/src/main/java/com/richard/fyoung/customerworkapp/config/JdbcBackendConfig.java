package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.JdbcOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcProductBackend;
import com.richard.fyoung.customerwork.tool.backend.OrderBackend;
import com.richard.fyoung.customerwork.tool.backend.ProductBackend;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 业务工具后端的 JDBC 装配（app 层选择，starter 只提供实现）。
 *
 * <p>{@code customer-work.tool-backend.mode=jdbc} 时声明 {@link JdbcOrderBackend}/{@link JdbcProductBackend}，
 * starter {@code ToolBackendConfig} 中以 {@code @ConditionalOnMissingBean} 注册的 Mock 后端自动让位。两者
 * 共用一个 HikariCP 连接池（复用 {@code session.mysql.*} 连接配置，与会话/工单持久化同库不同池，避免为
 * 演示后端再引入一套数据库凭据）。</p>
 *
 * <p>注意：{@code Jdbc*Backend} 构造即建表并写种子数据，故本配置一旦激活即要求 MySQL 可达（生产语义）；
 * 单元测试默认走 memory/mock，不触发本配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "customer-work.tool-backend.mode", havingValue = "jdbc")
public class JdbcBackendConfig {

    private final DataSource dataSource;

    public JdbcBackendConfig(CustomerWorkProperties properties) {
        this.dataSource = buildDataSource(properties.getSession().getMysql());
    }

    @Bean
    public OrderBackend jdbcOrderBackend() {
        return new JdbcOrderBackend(dataSource);
    }

    @Bean
    public ProductBackend jdbcProductBackend() {
        return new JdbcProductBackend(dataSource);
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（两个后端共用一个池）。 */
    private DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-tool-backend-pool");
        return ds;
    }
}
