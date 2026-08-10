package com.richard.fyoung.customerwork.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * app-server 侧 Nacos 服务注册装配（挂在 Nacos 总开关上）。
 *
 * <p>{@code customer-work.nacos.enabled=true} 时自动把本应用注册进 Nacos（服务名 = {@code spring.application.name}，
 * 取不到用常量兜底），供网关 {@code lb://} 发现路由；<b>不做额外独立开关</b>——「启用 Nacos」即注册。
 * 默认关闭（{@code enabled=false}）时本配置整体不生效，零行为、零额外连接。</p>
 *
 * @author owlzhangfq@gmail.com
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "customer-work.nacos", name = "enabled", havingValue = "true")
public class NacosServiceRegistrarConfig {

    /** 应用名取不到时的服务名兜底。 */
    private static final String DEFAULT_SERVICE_NAME = "customer-work-app-server";
    private static final String SCHEME_HTTP = "http";

    @Bean
    public NacosServiceRegistrar customerWorkNacosServiceRegistrar(
            CustomerWorkProperties properties,
            @Value("${spring.application.name:}") String applicationName,
            @Value("${server.port:8080}") int serverPort) {
        CustomerWorkProperties.Nacos cfg = properties.getNacos();
        NacosRegistration registration = NacosRegistration.builder()
            .serverAddr(cfg.getServerAddr())
            .namespace(cfg.getNamespace())
            .group(cfg.getGroup())
            .username(cfg.getUsername())
            .password(cfg.getPassword())
            .serviceName(StringUtils.hasText(applicationName) ? applicationName : DEFAULT_SERVICE_NAME)
            .ip(cfg.getInstanceIp())
            .port(serverPort)
            .scheme(SCHEME_HTTP)
            .contextPath("")
            .build();
        return new NacosServiceRegistrar(registration);
    }
}
