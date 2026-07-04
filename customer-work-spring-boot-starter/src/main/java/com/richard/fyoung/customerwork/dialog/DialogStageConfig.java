package com.richard.fyoung.customerwork.dialog;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 对话阶段存储配置。
 *
 * <p>按 {@code dialog.store-mode} 选择实现：默认 {@code memory}（进程内，仅单实例适用）；
 * {@code jdbc} 落地为 {@link JdbcDialogStageStore}，复用 {@code session.mysql.*} 连接配置，
 * 与审批工单 / 槽位收集共享同一 MySQL 实例。下游声明自己的 {@link DialogStageStore} Bean
 * 即可整体覆盖（如 Redis 实现）。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DialogStageConfig {

    private static final Logger log = LoggerFactory.getLogger(DialogStageConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(DialogStageStore.class)
    public DialogStageStore dialogStageStore(CustomerWorkProperties properties) {
        String mode = properties.getDialog().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("dialog stage store: jdbc (mysql, table=cw_dialog_stage)");
            return new JdbcDialogStageStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("dialog stage store: memory（进程内，多实例部署会导致阶段归零，生产建议 store-mode=jdbc）");
        return new InMemoryDialogStageStore();
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-dialogstage-pool");
        return ds;
    }
}
