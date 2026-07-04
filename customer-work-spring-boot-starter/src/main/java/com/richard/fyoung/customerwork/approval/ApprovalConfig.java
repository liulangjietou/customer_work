package com.richard.fyoung.customerwork.approval;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 审批工单存储配置。
 *
 * <p>按 {@code human-approval.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link JdbcApprovalStore}，保证审批单重启 / 多实例部署不丢失——对涉及资金的
 * 退款审批至关重要。jdbc 模式复用 {@code session.mysql.*} 的连接配置，与会话持久化共享同一
 * MySQL 实例（避免为审批单单独引入一套数据库凭据配置）。</p>
 *
 * <p>下游声明自己的 {@link ApprovalStore} Bean 即可整体覆盖（如 Redis 实现）。
 * {@link PendingApprovalService} 通过构造注入获取 {@link ApprovalStore}，业务逻辑与存储解耦。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class ApprovalConfig {

    private static final Logger log = LoggerFactory.getLogger(ApprovalConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(ApprovalStore.class)
    public ApprovalStore approvalStore(CustomerWorkProperties properties) {
        String mode = properties.getHumanApproval().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("approval store: jdbc (mysql, table=cw_approval)");
            return new JdbcApprovalStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("approval store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryApprovalStore();
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-approval-pool");
        return ds;
    }
}
