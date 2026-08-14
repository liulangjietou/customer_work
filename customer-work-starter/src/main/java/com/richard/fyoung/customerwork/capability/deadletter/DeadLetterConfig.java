package com.richard.fyoung.customerwork.capability.deadletter;

import com.richard.fyoung.customerwork.capability.deadletter.mapper.DeadLetterMapper;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 死信队列装配。
 *
 * <p>{@link DeadLetterHandler} 由业务方各自声明 Bean，这里用 {@link ObjectProvider} 收集全部实现——
 * starter 自身不知道也不该知道有哪些类型需要重投。</p>
 * @author owlzhangfq@gmail.com
 */
@Configuration
public class DeadLetterConfig {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConfig.class);

    private static final String STORE_MODE_JDBC = "jdbc";

    @Bean
    @ConditionalOnMissingBean(DeadLetterStore.class)
    public DeadLetterStore deadLetterStore(CustomerWorkProperties properties,
                                           ObjectProvider<DeadLetterMapper> mapperProvider) {
        String mode = properties.getDeadLetter().getStoreMode();
        if (STORE_MODE_JDBC.equalsIgnoreCase(mode)) {
            log.info("dead letter store: jdbc (MyBatis-Plus 实现, table=cw_dead_letter)");
            return new MybatisDeadLetterStore(mapperProvider.getObject());
        }
        log.info("dead letter store: memory (进程内；死信会随进程一起消失，生产必须 store-mode=jdbc)");
        return new InMemoryDeadLetterStore();
    }

    @Bean
    @ConditionalOnMissingBean(DeadLetterService.class)
    public DeadLetterService deadLetterService(DeadLetterStore store,
                                               CustomerWorkProperties properties,
                                               ObjectProvider<DeadLetterHandler> handlerProvider,
                                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        List<DeadLetterHandler> handlers = handlerProvider.orderedStream().toList();
        return new DeadLetterService(store, properties.getDeadLetter(), handlers,
            meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(DeadLetterRetryScheduler.class)
    public DeadLetterRetryScheduler deadLetterRetryScheduler(DeadLetterService deadLetterService) {
        return new DeadLetterRetryScheduler(deadLetterService);
    }
}
