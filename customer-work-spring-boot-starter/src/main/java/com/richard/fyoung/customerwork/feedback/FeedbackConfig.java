package com.richard.fyoung.customerwork.feedback;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 用户反馈存储配置。
 *
 * <p>按 {@code feedback.store-mode} 选择实现：默认 {@code memory}（进程内，离线可测）；
 * {@code jdbc} 落地为 {@link JdbcFeedbackStore}，复用 {@code session.mysql.*} 的连接配置。
 * 下游声明自己的 {@link FeedbackStore} Bean 即可整体覆盖（如 Redis 实现）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class FeedbackConfig {

    private static final Logger log = LoggerFactory.getLogger(FeedbackConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(FeedbackStore.class)
    public FeedbackStore feedbackStore(CustomerWorkProperties properties) {
        String mode = properties.getFeedback().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("feedback store: jdbc (mysql, table=cw_message_feedback)");
            return new JdbcFeedbackStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("feedback store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryFeedbackStore();
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-feedback-pool");
        return ds;
    }
}
