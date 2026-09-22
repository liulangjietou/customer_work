package com.richard.fyoung.customerwork.capability.typesafe;

import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jev 装配：仅 {@code customer-work.typesafe.enabled=true} 时生成，下游可声明同类型 Bean 覆盖。
 *
 * <p>未开启时容器里没有 {@link JevDecisionService}，各决策中间件经 {@code ObjectProvider} 取到 null
 * 后原样透传，行为与未接入完全一致。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "customer-work.typesafe", name = "enabled", havingValue = "true")
public class TypeSafeConfiguration {

    @Bean
    @ConditionalOnMissingBean(SystemOneClient.class)
    public SystemOneClient typeSafeSystemOneClient(CustomerWorkProperties properties,
                                                   ObjectProvider<MeterRegistry> meterRegistry) {
        return new TypeSafeSystemOneClient(properties.getTypesafe(), meterRegistry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(JevDecisionService.class)
    public JevDecisionService jevDecisionService(SystemOneClient client, CustomerWorkProperties properties,
                                                 ObjectProvider<MeterRegistry> meterRegistry) {
        return new JevDecisionService(client, properties.getTypesafe(), meterRegistry.getIfAvailable());
    }
}
