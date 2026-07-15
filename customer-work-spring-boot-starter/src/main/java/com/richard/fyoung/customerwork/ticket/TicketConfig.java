package com.richard.fyoung.customerwork.ticket;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 工单域装配：按 {@code customer-work.ticket.store-mode} 选择存储实现，并装配服务与 SLA 巡检器。
 *
 * <p>结构对齐 {@code HandoffConfig}：默认 {@code memory}（进程内，离线可测）；{@code jdbc} 落地为
 * {@link JdbcTicketStore}，复用 {@code session.mysql.*} 连接配置（与会话持久化共享同一 MySQL 实例）。
 * 三个 Bean 均 {@code @ConditionalOnMissingBean}，下游声明同类型 Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class TicketConfig {

    private static final Logger log = LoggerFactory.getLogger(TicketConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(TicketStore.class)
    public TicketStore ticketStore(CustomerWorkProperties properties) {
        String mode = properties.getTicket().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("ticket store: jdbc (mysql, tables=cw_ticket/cw_ticket_event)");
            return new JdbcTicketStore(buildDataSource(properties.getSession().getMysql()));
        }
        log.info("ticket store: memory (进程内，重启不保留，生产建议 store-mode=jdbc)");
        return new InMemoryTicketStore();
    }

    @Bean
    @ConditionalOnMissingBean(TicketService.class)
    public TicketService ticketService(TicketStore ticketStore,
                                       ObjectProvider<TicketEventListener> listenerProvider) {
        return new TicketService(ticketStore, listenerProvider);
    }

    @Bean
    @ConditionalOnMissingBean(TicketSlaScheduler.class)
    public TicketSlaScheduler ticketSlaScheduler(CustomerWorkProperties properties, TicketService ticketService) {
        return new TicketSlaScheduler(properties, ticketService);
    }

    /** 复用 session.mysql.* 连接配置构建独立连接池（惰性：首次取连接时才建立）。 */
    DataSource buildDataSource(CustomerWorkProperties.Session.Mysql m) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(m.resolveJdbcUrl());
        ds.setUsername(m.getUsername());
        ds.setPassword(m.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setPoolName("cw-ticket-pool");
        return ds;
    }
}
