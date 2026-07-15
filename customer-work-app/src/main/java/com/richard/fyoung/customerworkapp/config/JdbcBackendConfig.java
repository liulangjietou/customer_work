package com.richard.fyoung.customerworkapp.config;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.ComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcAfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcComplaintBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcKnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcMemberBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcOrderBackend;
import com.richard.fyoung.customerwork.tool.backend.JdbcProductBackend;
import com.richard.fyoung.customerwork.tool.backend.KnowledgeBackend;
import com.richard.fyoung.customerwork.tool.backend.MemberBackend;
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
 * <p>{@code customer-work.tool-backend.mode=jdbc} 时声明订单/商品/售后/会员/投诉/知识库六个 Jdbc 后端，
 * starter {@code ToolBackendConfig} 中以 {@code @ConditionalOnMissingBean} 注册的 Mock 后端自动全部让位。
 * 六个后端共用一个 HikariCP 连接池（复用 {@code session.mysql.*} 连接配置，与会话/工单持久化同库不同池，
 * 避免为演示后端再引入一套数据库凭据）。</p>
 *
 * <p>注意：{@code Jdbc*Backend} 构造即建表并写种子数据，故本配置一旦激活即要求 MySQL 可达（生产语义）；
 * 单元测试默认走 memory/mock，不触发本配置。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
@ConditionalOnProperty(name = "customer-work.tool-backend.mode", havingValue = "jdbc")
public class JdbcBackendConfig {

    private static final String DATA_SOURCE_BEAN = "toolBackendDataSource";

    /**
     * 工具后端共享数据源（复用 session.mysql.* 连接配置，六个后端 + 订单查询 Dao + 演示订单播种共用一个池）。
     *
     * <p>提升为 {@code @Bean}（条件同本配置：{@code tool-backend.mode=jdbc}）后，
     * {@code dao.UserOrderDao} / {@code service.DemoOrderSeeder} 可通过 {@code ObjectProvider<DataSource>}
     * 注入并在 mode!=jdbc（Bean 不存在）时优雅降级。Bean 名 {@value #DATA_SOURCE_BEAN} 与 starter 会话池
     * （customer-work-session-pool，非独立 DataSource Bean）不冲突。</p>
     */
    @Bean(DATA_SOURCE_BEAN)
    public DataSource toolBackendDataSource(CustomerWorkProperties properties) {
        CustomerWorkProperties.Session.Mysql m = properties.getSession().getMysql();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-tool-backend-pool");
        return ds;
    }

    @Bean
    public OrderBackend jdbcOrderBackend(DataSource toolBackendDataSource) {
        return new JdbcOrderBackend(toolBackendDataSource);
    }

    @Bean
    public ProductBackend jdbcProductBackend(DataSource toolBackendDataSource) {
        return new JdbcProductBackend(toolBackendDataSource);
    }

    @Bean
    public AfterSalesBackend jdbcAfterSalesBackend(DataSource toolBackendDataSource) {
        return new JdbcAfterSalesBackend(toolBackendDataSource);
    }

    @Bean
    public MemberBackend jdbcMemberBackend(DataSource toolBackendDataSource) {
        return new JdbcMemberBackend(toolBackendDataSource);
    }

    @Bean
    public ComplaintBackend jdbcComplaintBackend(DataSource toolBackendDataSource) {
        return new JdbcComplaintBackend(toolBackendDataSource);
    }

    @Bean
    public KnowledgeBackend jdbcKnowledgeBackend(DataSource toolBackendDataSource) {
        return new JdbcKnowledgeBackend(toolBackendDataSource);
    }
}
