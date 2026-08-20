package com.richard.fyoung.customerwork.data.outbox;

import com.richard.fyoung.customerwork.core.constant.StoreModes;
import com.richard.fyoung.customerwork.data.outbox.mapper.OutboxMessageMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.capability.deadletter.DeadLetterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;

/** Outbox Store、Handler 调度与服务装配。 */
@Configuration
public class OutboxConfig {

    private static final Logger log = LoggerFactory.getLogger(OutboxConfig.class);

    @Bean
    @ConditionalOnMissingBean(OutboxStore.class)
    public OutboxStore outboxStore(CustomerWorkProperties properties,
                                   ObjectProvider<OutboxMessageMapper> mapperProvider) {
        String configured = properties.getOutbox().getStoreMode();
        boolean ticketJdbc = StoreModes.isJdbc(properties.getTicket().getStoreMode());
        boolean jdbc = StoreModes.isJdbc(configured)
            || ("auto".equalsIgnoreCase(configured) && ticketJdbc);
        if (ticketJdbc && !jdbc) {
            throw new IllegalStateException(
                "ticket jdbc mode requires jdbc outbox to preserve transaction atomicity");
        }
        if (jdbc) {
            log.info("outbox store: jdbc (table=cw_outbox_message)");
            return new MybatisOutboxStore(mapperProvider.getObject());
        }
        log.info("outbox store: memory");
        return new InMemoryOutboxStore();
    }

    @Bean
    @ConditionalOnMissingBean(OutboxService.class)
    public OutboxService outboxService(OutboxStore store, CustomerWorkProperties properties,
                                       ObjectProvider<OutboxHandler> handlerProvider,
                                       ObjectProvider<MeterRegistry> meterRegistryProvider) {
        List<OutboxHandler> handlers = handlerProvider.orderedStream().toList();
        return new OutboxService(store, properties.getOutbox(), handlers,
            meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDispatcher.class)
    public OutboxDispatcher outboxDispatcher(OutboxService service) {
        return new OutboxDispatcher(service);
    }

    @Bean("deliveryQueuesHealthIndicator")
    @ConditionalOnMissingBean(name = "deliveryQueuesHealthIndicator")
    public DeliveryQueueHealthIndicator deliveryQueueHealthIndicator(
        OutboxStore outboxStore, ObjectProvider<DeadLetterStore> deadLetterStoreProvider,
        CustomerWorkProperties properties) {
        return new DeliveryQueueHealthIndicator(outboxStore,
            deadLetterStoreProvider.getIfAvailable(), properties);
    }
}
