package com.richard.fyoung.customeradmin.common.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 客服端库连接参数的<b>唯一注册点</b>。
 *
 * <p>{@link CustomerWorkDbProperties} 只有 {@code @ConfigurationProperties} 没有 {@code @Component}，
 * 这里的 {@code @EnableConfigurationProperties} 是它进容器的唯一入口——丢了不会编译失败，
 * 只会在启动时报 {@code NoSuchBeanDefinitionException}。此前各域属性类的注册散落在各自的
 * Provider 上（{@code ContentGuardGatewayProvider}、{@code DictGatewayProvider}、
 * {@code AgentCallStatsStoreConfig} 各一处），批量重构时最容易连同类级注解一起丢掉。
 * 收敛成一份连接参数之后，注册点也只留这一处。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration
@EnableConfigurationProperties(CustomerWorkDbProperties.class)
public class CustomerWorkDbConfig {
}
