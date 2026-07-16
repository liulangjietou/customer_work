package com.richard.fyoung.customerwork.ticket;

import com.richard.fyoung.customerwork.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.ticket.mapper.TicketEventMapper;
import com.richard.fyoung.customerwork.ticket.mapper.TicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工单域装配：按 {@code customer-work.ticket.store-mode} 选择存储实现，并装配服务与 SLA 巡检器。
 *
 * <p>默认 {@code memory}（进程内，离线可测）；{@code jdbc} 落地为 {@link MybatisTicketStore}，
 * Mapper 由独立的 {@code CustomerWorkPersistenceConfig}（MyBatis-Plus 环境）统一装配，此处经
 * {@link ObjectProvider} 惰性取用。三个 Bean 均 {@code @ConditionalOnMissingBean}，下游声明同类型
 * Bean 即可整体覆盖。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class TicketConfig {

    private static final Logger log = LoggerFactory.getLogger(TicketConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(TicketStore.class)
    public TicketStore ticketStore(CustomerWorkProperties properties,
                                   ObjectProvider<TicketMapper> ticketMapperProvider,
                                   ObjectProvider<TicketEventMapper> ticketEventMapperProvider) {
        String mode = properties.getTicket().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("ticket store: jdbc (MyBatis-Plus 实现, table=cw_ticket/cw_ticket_event)");
            return new MybatisTicketStore(ticketMapperProvider.getObject(), ticketEventMapperProvider.getObject());
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
}
