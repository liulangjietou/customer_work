package com.richard.fyoung.customerwork.slotfilling;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 槽位收集进度存储配置。
 *
 * <p>按 {@code slot-filling.store-mode} 选择实现：默认 {@code memory}（进程内）；{@code jdbc}
 * 落地为 {@link JdbcSlotFillingStore}，复用 {@code session.mysql.*} 连接配置，与审批工单
 * （见 {@code ApprovalConfig}）共享同一 MySQL 实例。下游声明自己的 {@link SlotFillingStore}
 * Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class SlotFillingConfig {

    private static final Logger log = LoggerFactory.getLogger(SlotFillingConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(SlotFillingStore.class)
    public SlotFillingStore slotFillingStore(CustomerWorkProperties properties) {
        String mode = properties.getSlotFilling().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("slot-filling store: jdbc (mysql, table=cw_slot_filling_progress)");
            return new JdbcSlotFillingStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("slot-filling store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemorySlotFillingStore();
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-slotfilling-pool");
        return ds;
    }
}
